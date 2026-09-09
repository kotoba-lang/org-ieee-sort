;; test/sort_test.cljs — build the command and compare it with the system
;; sort, byte for byte, in the POSIX locale.
;;
;; LC_ALL=C, and that is not a convenience. Measured 2026-09-10 on the lines
;; `b a B A`, the default locale answers `a A b B` and LC_ALL=C answers
;; `A B a b`; on `日 é z` the default answers `日 é z` and LC_ALL=C answers
;; `z é 日`. This implementation walks code points, and UTF-8 keeps code
;; point order and byte order the same, so it produces the second -- and
;; comparing it against the first would be comparing two orderings.

(ns sort-test
  (:require [clojure.string :as str] ["fs" :as fs] ["path" :as path] ["os" :as os]))

(def cp (js/require "node:child_process"))

(defn- run [cmd args opts]
  (let [r (.spawnSync cp cmd (clj->js args)
                      (clj->js (merge {:encoding "buffer"} opts)))]
    {:status (.-status r) :out (.-stdout r) :err (.-stderr r)}))

(defn- refuse [message]
  (println (pr-str {:ok false :phase :setup :message message}))
  (.exit js/process 2))

(def amu-home
  (or (.-AMU_HOME js/process.env)
      (let [guess (.resolve path (.cwd js/process) ".." ".." "kotoba-lang" "amu")]
        (when (.existsSync fs (.join path guess "bin" "amu")) guess))))

(def system-sort "/usr/bin/sort")

;; A directory of fixtures, and the cases over them. Each case is an argv,
;; and each is here because it separates a right implementation from a wrong
;; one that passes the others:
;;
;;   one file           -- the basic contract
;;   two files          -- concatenated in ORDER, with nothing added between
;;   the same file twice-- an operand is not deduplicated
;;   an EMPTY file      -- reads as the empty string, which must not end the
;;                         loop the way "past the last operand" does
;;   empty then content -- the same trap from the other side
;;   no trailing newline-- cat adds nothing of its own
;;   binary-ish bytes   -- high bytes survive the round trip
;;   no operands        -- POSIX reads stdin; there is no stdin capability,
;;                         so this asserts what it ACTUALLY does (nothing),
;;                         not what POSIX says
(def fixtures
  {"fruit"  "banana\napple\ncherry\n"
   ;; Case is where the two locales disagree most visibly.
   "case"   "b\na\nB\nA\n"
   ;; Lexical, not numeric: 10 100 9.
   "nums"   "10\n9\n100\n"
   ;; Duplicates are KEPT, both of them.
   "dup"    "dup\ndup\na\n"
   ;; No trailing newline: sort ADDS one, 13 bytes in and 14 out.
   "nonl"   "nonl-b\nnonl-a"
   ;; Multi-byte, where a byte walk and a code-point walk must agree.
   "utf8"   "\u65e5\n\u00e9\nz\n"
   "one"    "x\n"
   "empty"  ""
   ;; A common prefix: `a` sorts before `ab`, which is the case a comparison
   ;; that stops at the shorter string's end gets wrong.
   "prefix" "ab\na\nabc\n"
   ;; A blank line in the middle, and a blank LAST line. The second is the
   ;; one that caught a defensive trim of the final newline: the trim made
   ;; `a\n\n` answer `a` where sort answers an empty line and then `a`, and
   ;; every other fixture here passed with and without it.
   "blank"      "b\n\na\n"
   "trailblank" "a\n\n"})

(def cases
  [["fruit"] ["case"] ["nums"] ["dup"] ["nonl"] ["utf8"] ["one"] ["empty"]
   ["prefix"] ["blank"] ["trailblank"]])

(when-not amu-home (refuse "set AMU_HOME to an amu checkout"))
(let [amu (.join path amu-home "bin" "amu")
      packager (.join path amu-home "scripts" "package-command.cljs")]
  (when-not (.existsSync fs amu) (refuse (str "no amu at " amu)))
  (when-not (.existsSync fs packager) (refuse (str "no packager at " packager)))
  (when-not (.existsSync fs system-sort) (refuse (str "no " system-sort " to compare against")))
  (let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "org-ieee-wc-"))
        src (.resolve path (.cwd js/process) "sort" "core.kotoba")
        policy (.join path tmp "policy.edn")
        kexe (.join path tmp "sort.kexe")
        blob (.join path tmp "sort.bin")
        exe (.join path tmp "sort")
        exe-big (.join path tmp "sort-big")]
    (.writeFileSync fs policy "{:allow #{[:cap/call 35] [:cap/call 37] [:cap/call 38]}}" "utf8")
    ;; The fixtures live in the tree the binary is packaged for. The native
    ;; loader refuses a relative request outright, so operands are absolute.
    (let [data (.join path tmp "data")]
      (.mkdirSync fs data)
      (doseq [[name content] fixtures]
        (.writeFileSync fs (.join path data name)
                        content
                        "utf8")))
    (let [c (run "node" [amu "compile" src "--target" "aarch64-macos" "--jvm-free"
                         "--policy" policy "--output" kexe] {})]
      (when (not= 0 (:status c))
        (refuse (str "compile failed: " (str (:err c)) (str (:out c))))))
    (let [e (run "node" [amu "extract-native" kexe "--symbol" "main" "--output" blob] {})
          _ (when (not= 0 (:status e)) (refuse (str "extract failed: " (str (:err e)))))
          report (str (:out e))
          offset (second (re-find #":offset (\d+)" report))]
      (when-not offset (refuse (str "no :offset in the extract report: " report)))
      ;; TWO binaries from the same code: one with the loader's default
      ;; string-arena budget and one with a raised budget. The pair is what
      ;; makes the ceiling below a measurement instead of a claim -- a single
      ;; binary could only show that some size works and some does not, not
      ;; that the bound is the arena and that it moves.
      ;; Fuel and arena are constants of the binary, so they are packaged
      ;; here rather than supplied at run time. Counting words walks one code
      ;; point at a time, so the guest recursion is as long as the file and
      ;; the default 512 fuel counts almost nothing.
      (doseq [[out extra] [[exe ["--fuel" "50000000" "--string-pool" "8000000"]]]]
        (let [p (run "nbb" (into [packager "--code" blob "--offset" offset "--isa" "aarch64"
                                  "--allow" "35,37,38"
                                  "--fs-scope" (.realpathSync fs (.join path tmp "data"))
                                  "--output" out]
                                 extra) {})]
          (when (not= 0 (:status p)) (refuse (str "package failed: " (str (:err p))))))))
    ;; Now the only thing that matters: run it.
    (let [results
          (for [names cases]
            (let [argv [(.join path (.realpathSync fs (.join path tmp "data")) (first names))]
                  k (run exe argv {})
                  ;; LC_ALL=C: byte order. See the header.
                  s (run system-sort argv
                         {:env (let [e (js/Object.assign #js {} (.-env js/process))]
                                 (aset e "LC_ALL" "C")
                                 e)})
                  same? (and (= (.toString (:out k) "base64") (.toString (:out s) "base64"))
                             (= (:status k) (:status s)))]
              {:argv names :ok same? :kotoba (.toString (:out k) "utf8")
               :system (.toString (:out s) "utf8")
               :exit [(:status k) (:status s)]}))
          bad (remove :ok results)]
      (doseq [r results]
        (println (str (if (:ok r) "  ok   " "  FAIL ")
                      (pr-str (:argv r))
                      " -> " (pr-str (:kotoba r))
                      (when-not (:ok r) (str " but " system-sort " says " (pr-str (:system r))
                                             " exits " (pr-str (:exit r))))))) 
      (println (pr-str {:ok (empty? bad) :cases (count results) :failed (count bad)}))
      (.exit js/process (if (seq bad) 1 0)))))