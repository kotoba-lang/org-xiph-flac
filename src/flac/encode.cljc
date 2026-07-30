(ns flac.encode
  "FLAC encoding: fixed predictors and Rice-coded residuals.

   The pipeline is decide-by-counting throughout, which is why this is tractable
   where an LPC encoder is not. For each block and channel: try CONSTANT, then
   the five fixed predictors, then VERBATIM, cost each in bits, and keep the
   cheapest. For each residual: try every partition order, and within each
   partition every Rice parameter, and keep the cheapest. No search heuristics,
   no tuning constants.

   **No LPC.** Estimating linear-prediction coefficients well is most of what
   libFLAC does, and a bad estimate is worse than a fixed predictor. That is the
   one thing standing between this and the reference's ratio, and it is stated
   rather than papered over: the suite pins the size against `flac -5` with a
   measured bound instead of claiming parity.

   Stereo decorrelation is likewise not attempted — channels are coded
   independently, which is always legal and costs ratio on correlated material."
  (:require [flac.crc :as crc]))

;; ---------------------------------------------------------------------------
;; Bit writer
;; ---------------------------------------------------------------------------

(defn writer [] {:out (volatile! (transient [])) :cur (volatile! 0) :n (volatile! 0)})

(defn write-bit! [w b]
  (let [n (inc @(:n w)) c (+ (* 2 @(:cur w)) (if (zero? b) 0 1))]
    (if (= n 8)
      (do (vswap! (:out w) conj! c) (vreset! (:cur w) 0) (vreset! (:n w) 0))
      (do (vreset! (:cur w) c) (vreset! (:n w) n))))
  w)

(defn write-bits!
  "Write the low `n` bits of `value`, MSB-first. `n` may exceed 32; the value is
   split by division rather than shifted."
  [w n value]
  (loop [i (dec n)]
    (when (>= i 0)
      (write-bit! w (mod (quot value (Math/pow 2 i)) 2))
      (recur (dec i))))
  w)

(defn- write-signed! [w n value]
  (write-bits! w n (if (neg? value) (+ value (long (Math/pow 2 n))) value)))

(defn- write-unary! [w q]
  (dotimes [_ q] (write-bit! w 0))
  (write-bit! w 1))

(defn finish!
  "Pad to a byte boundary with zeros and return the bytes."
  [w]
  (let [n @(:n w)]
    (when (pos? n) (dotimes [_ (- 8 n)] (write-bit! w 0)))
    (persistent! @(:out w))))

;; ---------------------------------------------------------------------------
;; Residual costing
;; ---------------------------------------------------------------------------

(defn- zigzag [v] (if (neg? v) (- (* -2 v) 1) (* 2 v)))

(defn- rice-cost
  "Bits to code `values` with Rice parameter `p`, or nil if any quotient is
   implausibly long (which makes another parameter cheaper anyway)."
  [values p]
  (let [d (long (Math/pow 2 p))]
    (loop [s (seq values) total 0]
      (if-not s
        total
        (let [q (quot (zigzag (first s)) d)]
          (if (> q 100000)
            nil
            (recur (next s) (+ total q 1 p))))))))

(defn- best-param
  "The Rice parameter that codes `values` in the fewest bits."
  [values]
  (loop [p 0 best 0 best-cost nil]
    (if (> p 14)
      [best best-cost]
      (let [c (rice-cost values p)]
        (if (and c (or (nil? best-cost) (< c best-cost)))
          (recur (inc p) p c)
          (recur (inc p) best best-cost))))))

(defn- partitions-for
  "Split `residual` (which starts `order` samples into the block) the way a
   partition order requires. The **first** partition is shorter by the predictor
   order, and only the first."
  [residual block-size order p-order]
  (let [n-parts (long (Math/pow 2 p-order))
        per (quot block-size n-parts)]
    (when (and (zero? (rem block-size n-parts)) (>= (- per order) 0))
      (loop [i 0 at 0 out []]
        (if (= i n-parts)
          out
          (let [len (if (zero? i) (- per order) per)]
            (recur (inc i) (+ at len) (conj out (subvec residual at (+ at len))))))))))

(defn- plan-residual
  "Choose the partition order and per-partition Rice parameters that cost least.
   Returns `{:order p :params [...] :parts [[…] …] :bits n}`."
  [residual block-size order]
  (let [max-order (loop [p 0] (if (or (= p 8) (not (partitions-for residual block-size order (inc p))))
                                p
                                (recur (inc p))))]
    (reduce (fn [best p-order]
              (if-let [parts (partitions-for residual block-size order p-order)]
                (let [chosen (mapv best-param parts)
                      bits (+ 2 4 (reduce + (map (fn [[_ c]] (+ 4 (or c 1e12))) chosen)))]
                  (if (or (nil? best) (< bits (:bits best)))
                    {:order p-order :params (mapv first chosen) :parts parts :bits bits}
                    best))
                best))
            nil
            (range 0 (inc max-order)))))

;; ---------------------------------------------------------------------------
;; Subframe choice
;; ---------------------------------------------------------------------------

(def ^:private fixed-coefficients {0 [] 1 [1] 2 [2 -1] 3 [3 -3 1] 4 [4 -6 4 -1]})

(defn- fixed-residual
  "The residual of the order-`k` fixed predictor over `samples`."
  [samples k]
  (let [coeffs (get fixed-coefficients k)]
    (loop [i k out (transient [])]
      (if (= i (count samples))
        (persistent! out)
        (let [pred (loop [j 0 acc 0]
                     (if (= j k)
                       acc
                       (recur (inc j) (+ acc (* (nth coeffs j) (nth samples (- i j 1)))))))]
          (recur (inc i) (conj! out (- (nth samples i) pred))))))))

(defn- choose-subframe
  "The cheapest legal encoding of one channel's block."
  [samples bps]
  (let [n (count samples)
        verbatim {:kind :verbatim :bits (+ 8 (* n bps))}
        constant (when (apply = samples) {:kind :constant :bits (+ 8 bps)})
        fixed (keep (fn [k]
                      (when (> n k)
                        (let [res (fixed-residual samples k)
                              plan (plan-residual res n k)]
                          (when plan
                            {:kind :fixed :order k :residual res :plan plan
                             :bits (+ 8 (* k bps) (:bits plan))}))))
                    (range 0 5))]
    (->> (concat [verbatim] (when constant [constant]) fixed)
         (sort-by :bits)
         first)))

(defn- write-subframe! [w subframe samples bps]
  (write-bit! w 0)                                          ; padding
  (case (:kind subframe)
    :constant (do (write-bits! w 6 0)
                  (write-bit! w 0)                          ; no wasted bits
                  (write-signed! w bps (first samples)))
    :verbatim (do (write-bits! w 6 1)
                  (write-bit! w 0)
                  (doseq [x samples] (write-signed! w bps x)))
    :fixed (let [{:keys [order plan]} subframe]
             (write-bits! w 6 (+ 8 order))
             (write-bit! w 0)
             (doseq [x (take order samples)] (write-signed! w bps x))
             (write-bits! w 2 0)                            ; 4-bit Rice params
             (write-bits! w 4 (:order plan))
             (doseq [[param part] (map vector (:params plan) (:parts plan))]
               (write-bits! w 4 param)
               (let [d (long (Math/pow 2 param))]
                 (doseq [v part]
                   (let [u (zigzag v)]
                     (write-unary! w (quot u d))
                     (when (pos? param) (write-bits! w param (mod u d))))))))))

;; ---------------------------------------------------------------------------
;; Frames
;; ---------------------------------------------------------------------------

(def ^:private rate-codes
  {88200 1 176400 2 192000 3 8000 4 16000 5 22050 6 24000 7 32000 8
   44100 9 48000 10 96000 11})

(def ^:private depth-codes {8 1 12 2 16 4 20 5 24 6 32 7})

(def ^:private block-codes {256 8 512 9 1024 10 2048 11 4096 12 8192 13 16384 14 32768 15
                            192 1 576 2 1152 3 2304 4 4608 5})

(defn- write-utf8-number!
  "FLAC's UTF-8-like coding, which the format extends to 36 bits."
  [w value]
  (cond
    (< value 0x80) (write-bits! w 8 value)
    :else
    (let [[lead n] (cond (< value 0x800) [0xc0 1]
                         (< value 0x10000) [0xe0 2]
                         (< value 0x200000) [0xf0 3]
                         (< value 0x4000000) [0xf8 4]
                         (< value 0x80000000) [0xfc 5]
                         :else [0xfe 6])
          shift (Math/pow 2 (* 6 n))]
      (write-bits! w 8 (+ lead (quot value shift)))
      (loop [i (dec n)]
        (when (>= i 0)
          (write-bits! w 8 (+ 0x80 (mod (quot value (Math/pow 2 (* 6 i))) 64)))
          (recur (dec i)))))))

(defn- frame-bytes
  "One complete frame, CRCs included."
  [channels frame-number sample-rate bps block-size]
  (let [w (writer)]
    (write-bits! w 14 0x3ffe)
    (write-bit! w 0)                                        ; reserved
    (write-bit! w 0)                                        ; fixed block size => frame number
    (let [bs-code (get block-codes block-size 7)            ; 7 = explicit 16-bit
          sr-code (get rate-codes sample-rate 0)            ; 0 = from STREAMINFO
          bps-code (get depth-codes bps 0)]
      (write-bits! w 4 bs-code)
      (write-bits! w 4 sr-code)
      (write-bits! w 4 (dec (count channels)))              ; independent channels
      (write-bits! w 3 bps-code)
      (write-bit! w 0)                                      ; reserved
      (write-utf8-number! w frame-number)
      (when (= bs-code 7) (write-bits! w 16 (dec block-size)))
      ;; the CRC-8 covers the header as written so far, which is byte-aligned here
      (let [header (finish! w)
            w2 (writer)]
        (doseq [b header] (write-bits! w2 8 b))
        (write-bits! w2 8 (crc/crc8 header))
        (doseq [ch channels]
          (write-subframe! w2 (choose-subframe ch bps) ch bps))
        (let [body (finish! w2)]
          (into body (let [c (crc/crc16 body)]
                       [(quot c 256) (mod c 256)])))))))

;; ---------------------------------------------------------------------------
;; Stream
;; ---------------------------------------------------------------------------

(def ^:private default-block-size 4096)

(defn encode
  "Encode `channels` (a vector of equal-length signed-integer sample vectors)
   into a FLAC stream → a vector of unsigned bytes.

       (encode {:channels [[…] […]] :sample-rate 44100 :bits 16})

   `:block-size` defaults to 4096. The STREAMINFO MD5 is written as all zeros,
   which the format defines as *unknown* — there is no MD5 in this workspace, and
   `flac -t` reports it as unverified rather than as a failure."
  [{:keys [channels sample-rate bits block-size]
    :or {sample-rate 44100 bits 16 block-size default-block-size}}]
  (let [chs (mapv vec channels)
        n-ch (count chs)]
    (when (zero? n-ch)
      (throw (ex-info "flac: no channels" {:reason :bad-input})))
    (when (apply not= (map count chs))
      (throw (ex-info "flac: channels have different lengths"
                      {:reason :bad-input :lengths (mapv count chs)})))
    (when-not (contains? depth-codes bits)
      (throw (ex-info (str "flac: cannot write " bits "-bit samples")
                      {:reason :unsupported :bits bits})))
    (when (or (> n-ch 8) (neg? n-ch))
      (throw (ex-info "flac: FLAC allows at most eight channels"
                      {:reason :unsupported :channels n-ch})))
    (let [total (count (first chs))
          limit (long (Math/pow 2 (dec bits)))]
      (doseq [ch chs]
        (when (some #(or (>= % limit) (< % (- limit))) ch)
          (throw (ex-info (str "flac: a sample does not fit in " bits " bits")
                          {:reason :bad-input :bits bits}))))
      (let [frames (loop [at 0 i 0 out [] sizes []]
                     (if (>= at total)
                       {:bytes out :sizes sizes}
                       (let [len (min block-size (- total at))
                             blk (mapv #(subvec % at (+ at len)) chs)
                             f (frame-bytes blk i sample-rate bits len)]
                         (recur (+ at len) (inc i) (into out f) (conj sizes (count f))))))
            si (let [w (writer)]
                 (write-bits! w 16 (if (> total block-size) block-size (max total 16)))
                 (write-bits! w 16 block-size)
                 (write-bits! w 24 (if (seq (:sizes frames)) (apply min (:sizes frames)) 0))
                 (write-bits! w 24 (if (seq (:sizes frames)) (apply max (:sizes frames)) 0))
                 (write-bits! w 20 sample-rate)
                 (write-bits! w 3 (dec n-ch))
                 (write-bits! w 5 (dec bits))
                 (write-bits! w 36 total)
                 (dotimes [_ 16] (write-bits! w 8 0))       ; MD5 unknown
                 (finish! w))]
        (into (into (into [0x66 0x4c 0x61 0x43]             ; "fLaC"
                          [0x80 0x00 0x00 0x22])            ; last block, STREAMINFO, 34 bytes
                    si)
              (:bytes frames))))))
