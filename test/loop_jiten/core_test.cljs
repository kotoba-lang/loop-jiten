(ns loop-jiten.core-test
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [clojure.test :refer [deftest is testing]]
            [loop-jiten.core :as lj]))

(defn- tmpdir []
  (str (fs/mkdtempSync (path/join (os/tmpdir) "loop-jiten-test-"))))

(def one-entry
  "[{:jiten.entry/id :entry/a
     :jiten.entry/title \"A\"
     :jiten.entry/lang \"en\"
     :jiten.entry/form :article
     :jiten.entry/topic-kind :artifact
     :jiten.entry/notability {:jiten.notability/basis :workspace
                              :jiten.notability/sources [\"fixture record\"]
                              :jiten.notability/note \"fixture\"}
     :jiten.entry/links [{:jiten.link/to :entry/missing :jiten.link/kind :see-also}]
     :jiten.entry/body [{:jiten.section/id :lead
                         :jiten.section/heading \"Lead\"
                         :jiten.section/statements
                         [{:jiten.statement/id :s/a1
                           :jiten.statement/text \"A is a fixture.\"
                           :jiten.statement/source \"fixture\"
                           :jiten.statement/confidence :documented}]}]}]")

(deftest observe-reads-a-corpus-directory
  (let [d (tmpdir)]
    (fs/writeFileSync (path/join d "a.edn") one-entry)
    (let [o (lj/observe d)]
      (is (= 1 (count (:entries o))))
      (is (= 1 (count (:files o))))
      (is (empty? (:skipped o))))))

(deftest an-unparsable-file-is-reported-not-skipped-silently
  (testing "a corpus that quietly shrank because one file stopped parsing looks
            exactly like a corpus that was always that size"
    (let [d (tmpdir)]
      (fs/writeFileSync (path/join d "good.edn") one-entry)
      (fs/writeFileSync (path/join d "broken.edn") "[{:jiten.entry/id :entry/x")
      (let [o (lj/observe d)]
        (is (= 1 (count (:entries o))))
        (is (= 1 (count (:skipped o))))
        (is (re-find #"broken\.edn" (:file (first (:skipped o)))))))))

(deftest a-wrong-shaped-file-is-also-reported
  (let [d (tmpdir)]
    (fs/writeFileSync (path/join d "map.edn") "{:not \"a vector of entries\"}")
    (let [o (lj/observe d)]
      (is (= 1 (count (:skipped o))))
      (is (= "not a vector of entry maps" (:reason (first (:skipped o))))))))

(deftest evaluate-delegates-every-number-to-jiten
  (let [e (lj/evaluate (cljs.reader/read-string one-entry))]
    (is (= 1 (get-in e [:stats :entries])))
    (is (= 1 (get-in e [:stats :statements])))
    (is (empty? (:errors e)))
    (testing "a red link is a warning, so it does not block the cycle"
      (is (= 1 (get-in e [:stats :red-links])))
      (is (seq (:warnings e))))))

(deftest decide-ranks-the-gap-the-corpus-named
  (let [d (lj/decide (lj/evaluate (cljs.reader/read-string one-entry)))]
    (is (= [:entry/missing] (mapv :jiten.link/to (:write-next d))))
    (is (empty? (:blocking d)))))

(deftest a-cycle-appends-exactly-one-ledger-line
  (let [d (tmpdir)
        corpus-dir (path/join d "corpus")
        ledger (path/join d "ledger" "l.edn")
        report (path/join d "target" "r.md")]
    (fs/mkdirSync corpus-dir #js {:recursive true})
    (fs/writeFileSync (path/join corpus-dir "a.edn") one-entry)
    (let [l1 (lj/cycle! {:dir corpus-dir :ledger-file ledger :report-file report :as-of "2026-07-29"})
          l2 (lj/cycle! {:dir corpus-dir :ledger-file ledger :report-file report :as-of "2026-07-30"})
          lines (remove empty? (clojure.string/split-lines (str (fs/readFileSync ledger "utf8"))))]
      (is (= 1 (:jiten/seq l1)))
      (is (= 2 (:jiten/seq l2)))
      (is (= 2 (count lines)) "append-only: the first line must survive the second cycle")
      (testing "and the earlier line is unchanged"
        (is (= "2026-07-29" (:jiten/as-of (cljs.reader/read-string (first lines))))))
      (is (fs/existsSync report)))))

(deftest the-report-carries-sources-not-just-counts
  (let [d (tmpdir)
        corpus-dir (path/join d "corpus")]
    (fs/mkdirSync corpus-dir #js {:recursive true})
    (fs/writeFileSync (path/join corpus-dir "a.edn") one-entry)
    (let [o (lj/observe corpus-dir)
          e (lj/evaluate (:entries o))
          r (lj/act {:observation o :evaluation e :decision (lj/decide e) :as-of "2026-07-29"})]
      (is (re-find #"What each source is holding up" r))
      (is (re-find #"fixture" r))
      (is (re-find #"entry/missing" r)))))
