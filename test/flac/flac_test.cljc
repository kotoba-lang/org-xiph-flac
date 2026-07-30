(ns flac.flac-test
  "Runtime-agnostic FLAC suite.

   The assertion that matters is **bit-exactness against the reference decoder**,
   not that decoding runs: each fixture carries the reference's own output, so
   ClojureScript checks the same thing the JVM does. The oracle suite adds breadth
   over levels, depths and sources."
  (:require [flac.bits :as bits]
            [flac.core :as flac]
            [flac.fixtures :as fixtures]
            [riff.core :as riff]
            #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])))

(defn- b64->bytes [s]
  #?(:clj (mapv #(bit-and (int %) 0xff)
                (.decode (java.util.Base64/getDecoder) ^String s))
     :cljs (let [d (js/atob s)]
             (mapv #(.charCodeAt d %) (range (.-length d))))))

(defn- reason-of [f]
  (try (f) ::no-throw
       (catch #?(:clj Exception :cljs :default) e (:reason (ex-data e)))))

(defn- fixture [name] (get fixtures/files name))
(defn- flac-bytes [name] (b64->bytes (:flac (fixture name))))
(defn- reference-samples [name] (riff/samples (b64->bytes (:wav (fixture name)))))

;; ---------------------------------------------------------------------------
;; The whole point
;; ---------------------------------------------------------------------------

(deftest decodes-bit-exactly
  (doseq [name (sort (keys fixtures/files))]
    (testing name
      (let [ours (:channels (flac/decode (flac-bytes name)))]
        (is (= (reference-samples name) ours)
            "every sample must equal the reference decoder's output")))))

(deftest sample-counts-agree-with-stream-info
  (doseq [name (sort (keys fixtures/files))]
    (testing name
      (let [si (flac/stream-info (flac-bytes name))
            out (flac/decode (flac-bytes name))]
        (is (= (:total-samples si) (:samples out)))
        (is (= (:channels si) (count (:channels out))))
        (is (every? #(= (:samples out) (count %)) (:channels out))
            "channels must be the same length")))))

;; ---------------------------------------------------------------------------
;; Metadata
;; ---------------------------------------------------------------------------

(deftest reads-stream-info
  (let [si (flac/stream-info (flac-bytes "sine-l8"))]
    (is (= 44100 (:sample-rate si)))
    (is (= 2 (:channels si)))
    (is (= 16 (:bits si)))
    (is (pos? (:total-samples si)))
    (is (= 16 (count (:md5 si))) "the MD5 is recorded, not verified")
    (is (<= (:min-block-size si) (:max-block-size si))))
  (testing "a 24-bit stream reports its real depth"
    (is (= 24 (:bits (flac/stream-info (flac-bytes "deep-l8"))))))
  (testing "an 8-bit source"
    (let [si (flac/stream-info (flac-bytes "quiet-l5"))]
      (is (= 8 (:bits si)))
      (is (= 1 (:channels si)))
      (is (= 8000 (:sample-rate si))))))

(deftest reads-metadata-blocks
  (let [blocks (flac/metadata (flac-bytes "sine-l8"))]
    (is (= :stream-info (:type (first blocks))) "STREAMINFO must come first")
    (is (:last? (last blocks)) "the last block says so")
    (is (every? #(contains? % :offset) blocks))
    (testing "a VORBIS_COMMENT block is parsed when present"
      (when-let [vc (first (filter #(= :vorbis-comment (:type %)) blocks))]
        (is (string? (:vendor vc)))
        (is (vector? (:comments vc)))))))

;; ---------------------------------------------------------------------------
;; Bit reader
;; ---------------------------------------------------------------------------

(deftest bit-reader
  (testing "bits are most-significant-first"
    (is (= 1 (bits/read-bit (bits/reader [0x80]))))
    (is (= 0x3ffe (bits/read-bits (bits/reader [0xff 0xf8]) 14)) "the frame sync code"))
  (testing "signed fields are two's complement"
    (is (= -1 (bits/read-signed (bits/reader [0xff]) 8)))
    (is (= 127 (bits/read-signed (bits/reader [0x7f]) 8)))
    ;; the top five bits of 0xf0 are 11110 = 30, which is -2 in five bits
    (is (= -2 (bits/read-signed (bits/reader [0xf0]) 5)))
    (is (= -16 (bits/read-signed (bits/reader [0x80]) 5)) "10000 is the most negative")
    (is (= 0 (bits/read-signed (bits/reader [0xff]) 0))))
  (testing "unary counts zeros up to the terminating one"
    (is (= 0 (bits/read-unary (bits/reader [0x80]))))
    (is (= 3 (bits/read-unary (bits/reader [0x10]))))
    (is (= 8 (bits/read-unary (bits/reader [0x00 0x80])))))
  (testing "the UTF-8-like frame number goes past what Unicode allows"
    ;; FLAC extends the original scheme to 36 bits — seven bytes. A decoder
    ;; written against modern UTF-8 rejects a legal frame beyond 2^31 samples.
    (is (= 0 (bits/read-utf8-number (bits/reader [0x00]))))
    (is (= 127 (bits/read-utf8-number (bits/reader [0x7f]))))
    (is (= 128 (bits/read-utf8-number (bits/reader [0xc2 0x80]))))
    (is (= 2048 (bits/read-utf8-number (bits/reader [0xe0 0xa0 0x80]))))
    ;; 0xFE opens the seven-byte form: no payload bits in the lead byte, then six
    ;; continuation bytes of six bits each = 36 bits. 0x90 contributes 16, so this
    ;; is 16 * 64^5 = 2^34 — a frame number no four-byte UTF-8 decoder can reach.
    (is (= 17179869184 (bits/read-utf8-number
                        (bits/reader [0xfe 0x90 0x80 0x80 0x80 0x80 0x80]))))
    (is (= 68719476735 (bits/read-utf8-number
                        (bits/reader [0xfe 0xbf 0xbf 0xbf 0xbf 0xbf 0xbf])))
        "2^36-1, the largest frame number the format can express"))
  (testing "reading past the end says so"
    (is (= :truncated (reason-of #(bits/read-bits (bits/reader [0x00]) 9))))))

;; ---------------------------------------------------------------------------
;; Refusals
;; ---------------------------------------------------------------------------

(deftest rejects-what-it-cannot-honestly-read
  (testing "not a FLAC stream"
    (is (false? (flac/flac? [1 2 3 4])))
    (is (= :not-flac (reason-of #(flac/metadata (vec (repeat 40 0x41)))))))
  (testing "truncated metadata"
    (is (= :truncated (reason-of #(flac/metadata (into flac/signature [0x00 0x00])))))
    (is (= :truncated (reason-of #(flac/metadata (into flac/signature [0x80 0xff 0xff 0xff]))))))
  (testing "a stream whose first block is not STREAMINFO"
    ;; type 1 is PADDING; make it the first and last block
    (let [bad (into (into flac/signature [0x81 0x00 0x00 0x04]) [0 0 0 0])]
      (is (= :bad-metadata (reason-of #(flac/stream-info bad))))))
  (testing "a corrupt frame is detected rather than yielding wrong samples"
    (let [full (flac-bytes "sine-l8")
          b (last (flac/metadata full))
          audio-start (+ (:offset b) (:length b))
          ;; flip a bit inside the first frame header
          broken (assoc full (+ audio-start 1) (bit-xor (nth full (+ audio-start 1)) 0x40))]
      (is (contains? #{:bad-frame-header :bad-subframe :bad-residual :truncated
                       :unsupported :output-too-large}
                     (reason-of #(flac/decode broken))))))
  (testing "an output ceiling bounds a hostile stream"
    (is (= :output-too-large
           (reason-of #(flac/decode (flac-bytes "sine-l8") {:max-samples 10}))))))

(deftest signature-sniff
  (is (flac/flac? (flac-bytes "sine-l8")))
  (is (false? (flac/flac? [])))
  (is (false? (flac/flac? [0x66 0x4c 0x61 0x44]))))
