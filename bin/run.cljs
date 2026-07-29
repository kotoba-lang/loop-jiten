#!/usr/bin/env nbb
;; One cycle. Writes target/loop-jiten-report.md and appends one ledger line.
;;
;;   nbb --classpath "../jiten/src:src" bin/run.cljs [--as-of YYYY-MM-DD]
(ns run
  (:require ["child_process" :as cp]
            [clojure.string :as str]
            [loop-jiten.core :as loop-jiten]))

(def argv (vec *command-line-args*))

(defn- arg [flag default]
  (let [i (.indexOf (into-array argv) flag)]
    (if (neg? i) default (nth argv (inc i) default))))

(def as-of
  (arg "--as-of" (str/trim (str (cp/execSync "date -u +%Y-%m-%d" #js {:encoding "utf8"})))))

(let [line (loop-jiten/cycle! {:as-of as-of})]
  (println "loop-jiten cycle" (:jiten/seq line) "—" as-of)
  (println "  entries    " (get-in line [:jiten/stats :entries]))
  (println "  statements " (get-in line [:jiten/stats :statements]))
  (println "  sources    " (get-in line [:jiten/stats :distinct-sources]))
  (println "  red links  " (get-in line [:jiten/stats :red-links]))
  (println "  errors     " (:jiten/errors line))
  (println "  write-next " (pr-str (:jiten/write-next line)))
  (println "  report     target/loop-jiten-report.md"))
