(ns flac.flac-oracle-test
  "Conformance against the reference implementation (`flac`), sample for sample.

   FLAC is lossless, so there is exactly one right answer and a decoder either
   produces it or does not. The portable suite proves that for five recorded
   files; this adds the breadth a recording cannot carry — every compression
   level, three bit depths, mono and stereo, and sources chosen so that each
   subframe type and each stereo decorrelation mode actually occurs.

   Skipped loudly when `flac` or `ffmpeg` is missing."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [flac.core :as flac]
            [riff.core :as riff])
  (:import [java.io File]
           [java.nio.file Files]))

(defn- have-tools? []
  (try (and (zero? (:exit (shell/sh "bash" "-c" "command -v flac")))
            (zero? (:exit (shell/sh "bash" "-c" "command -v ffmpeg"))))
       (catch Exception _ false)))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory
            "org-xiph-flac-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf [^File f] (doseq [c (reverse (file-seq f))] (.delete ^File c)))
(defn- read-ubytes [^File f] (mapv #(bit-and (int %) 0xff) (Files/readAllBytes (.toPath f))))

(defn- sh! [dir & args]
  (let [{:keys [exit err out]} (apply shell/sh (concat args [:dir dir]))]
    (is (zero? exit) (str (first args) " failed: " err out))
    exit))

(defn- source!
  "Make a WAV with ffmpeg → its path name."
  [dir name lavfi channels codec]
  (sh! dir "ffmpeg" "-hide_banner" "-v" "error" "-f" "lavfi" "-i" lavfi
       "-ac" (str channels) "-c:a" codec name "-y")
  name)

(defn- encode! [dir wav level out]
  (sh! dir "flac" "--totally-silent" "-f" (str "-" level) "-o" out wav)
  (read-ubytes (io/file dir out)))

(defn- reference-decode!
  "What `flac -d` produces, as deinterleaved samples."
  [dir flac-name wav-out]
  (sh! dir "flac" "--totally-silent" "-f" "-d" "-o" wav-out flac-name)
  (riff/samples (read-ubytes (io/file dir wav-out))))

(def ^:private sources
  ;; name, lavfi source, channels, sample format — chosen for subframe coverage:
  ;; a sine gives LPC, noise gives verbatim and high-order LPC, near-silence gives
  ;; CONSTANT, and two identical channels drive mid/side hardest
  [["sine16" "sine=frequency=440:duration=0.25:sample_rate=44100" 2 "pcm_s16le"]
   ["noise16" "anoisesrc=duration=0.25:sample_rate=44100:amplitude=0.8" 1 "pcm_s16le"]
   ["quiet8" "sine=frequency=1:duration=0.25:sample_rate=8000" 1 "pcm_u8"]
   ["deep24" "sine=frequency=440:duration=0.25:sample_rate=48000" 2 "pcm_s24le"]])

(deftest decodes-every-level-bit-exactly
  (if-not (have-tools?)
    (println "SKIP flac.flac-oracle-test: flac or ffmpeg not available")
    (let [dir (temp-dir)]
      (try
        (doseq [[name lavfi channels codec] sources]
          (let [wav (source! dir (str name ".wav") lavfi channels codec)]
            (doseq [level (range 0 9)]
              (testing (str name " at -" level)
                (let [fl (str name "-" level ".flac")
                      bytes (encode! dir wav level fl)
                      expected (reference-decode! dir fl (str name "-" level ".dec.wav"))
                      ours (:channels (flac/decode bytes))]
                  (is (= expected ours)
                      (str name " -" level " did not decode bit-exactly")))))))
        (finally (rm-rf dir))))))

(deftest stream-info-matches-the-reference
  (if-not (have-tools?)
    (println "SKIP flac.flac-oracle-test: reference tools not available")
    (let [dir (temp-dir)]
      (try
        (doseq [[name lavfi channels codec] sources]
          (testing name
            (let [wav (source! dir (str name ".wav") lavfi channels codec)
                  src (riff/parse (read-ubytes (io/file dir wav)))
                  bytes (encode! dir wav 5 (str name "-si.flac"))
                  si (flac/stream-info bytes)
                  out (flac/decode bytes)]
              (is (= (:sample-rate src) (:sample-rate si)))
              (is (= (:channels src) (:channels si)))
              (is (= (:bits src) (:bits si)))
              (is (= (:frames src) (:total-samples si)))
              (is (= (:total-samples si) (:samples out))
                  "we must decode exactly as many samples as the header declares"))))
        (finally (rm-rf dir))))))

(deftest handles-unusual-block-sizes-and-rates
  ;; `-b` changes the block size, which changes the frame header's size code and
  ;; can force the explicit 8/16-bit forms rather than a table entry.
  (if-not (have-tools?)
    (println "SKIP flac.flac-oracle-test: reference tools not available")
    (let [dir (temp-dir)]
      (try
        (let [wav (source! dir "bs.wav" "sine=frequency=300:duration=0.3:sample_rate=44100"
                           2 "pcm_s16le")]
          (doseq [bs [1152 2048 4096 4608]]
            (testing (str "block size " bs)
              (let [fl (str "bs-" bs ".flac")]
                (sh! dir "flac" "--totally-silent" "-f" "-5" "-b" (str bs) "-o" fl wav)
                (let [expected (reference-decode! dir fl (str "bs-" bs ".dec.wav"))
                      ours (:channels (flac/decode (read-ubytes (io/file dir fl))))]
                  (is (= expected ours)))))))
        (testing "a sample rate that is not in the frame header's table"
          (let [wav (source! dir "odd.wav" "sine=frequency=440:duration=0.2:sample_rate=37000"
                             1 "pcm_s16le")
                fl "odd.flac"]
            (sh! dir "flac" "--totally-silent" "-f" "-5" "-o" fl wav)
            (let [bytes (read-ubytes (io/file dir fl))]
              (is (= 37000 (:sample-rate (flac/stream-info bytes))))
              (is (= (reference-decode! dir fl "odd.dec.wav")
                     (:channels (flac/decode bytes)))))))
        (finally (rm-rf dir))))))

(deftest a-longer-file
  (if-not (have-tools?)
    (println "SKIP flac.flac-oracle-test: reference tools not available")
    (let [dir (temp-dir)]
      (try
        (let [wav (source! dir "long.wav" "sine=frequency=440:duration=3:sample_rate=44100"
                           2 "pcm_s16le")
              fl "long.flac"
              bytes (encode! dir wav 8 fl)
              out (flac/decode bytes)]
          (is (= 132300 (:samples out)) "three seconds at 44.1 kHz")
          (is (= (reference-decode! dir fl "long.dec.wav") (:channels out))))
        (finally (rm-rf dir))))))

(deftest metadata-blocks-a-real-encoder-emits
  (if-not (have-tools?)
    (println "SKIP flac.flac-oracle-test: reference tools not available")
    (let [dir (temp-dir)]
      (try
        (let [wav (source! dir "tags.wav" "sine=frequency=440:duration=0.1:sample_rate=44100"
                           2 "pcm_s16le")
              fl "tags.flac"]
          (sh! dir "flac" "--totally-silent" "-f" "-5" "-o" fl
               "-T" "TITLE=A Test" "-T" "ARTIST=Nobody" wav)
          (let [blocks (flac/metadata (read-ubytes (io/file dir fl)))
                vc (first (filter #(= :vorbis-comment (:type %)) blocks))]
            (is (= :stream-info (:type (first blocks))))
            (is (some? vc) "the encoder wrote a VORBIS_COMMENT block")
            (is (some #(= "TITLE=A Test" %) (:comments vc)))
            (is (some #(= "ARTIST=Nobody" %) (:comments vc)))
            (is (:last? (last blocks)))
            (testing "and the audio still decodes past all of them"
              (is (pos? (:samples (flac/decode (read-ubytes (io/file dir fl)))))))))
        (finally (rm-rf dir))))))
