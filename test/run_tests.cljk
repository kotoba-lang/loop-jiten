(ns run-tests
  (:require ["node:process" :as process]
            [cljs.test :as t]
            [loop-jiten.core-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (process/exit 1)))

(t/run-tests 'loop-jiten.core-test)
