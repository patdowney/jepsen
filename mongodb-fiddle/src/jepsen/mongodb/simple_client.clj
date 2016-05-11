(ns jepsen.mongodb.simple-client
  "Compare-and-set against a single document."
  (:refer-clojure :exclude [test])
  (:require [clojure [pprint :refer :all]
             [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [debug info warn]]
            [clojure.core.reducers :as r]
            [jepsen [core :as jepsen]
             [util :as util :refer [meh timeout]]
             [control :as c :refer [|]]
             [client :as client]
             [checker :as checker]
             [independent :as independent]
             [generator :as gen]
             [nemesis :as nemesis]
             [store :as store]
             [report :as report]
             [tests :as tests]]
            [jepsen.control [net :as net]
             [util :as net/util]]
            [jepsen.os.debian :as debian]
            [jepsen.checker.timeline :as timeline]
            [knossos.core :as knossos]
            [knossos.model :as model]
            [knossos.op :as op]
            [jepsen.mongodb.core :refer :all]
            [jepsen.mongodb.mongo :as m]
            [clojure.set :as set]
            [puppetlabs.structured-logging.core :refer [maplog]])
  (:import (clojure.lang ExceptionInfo)))

(defn read-doc [op coll id]
  (maplog [:stash :info] op "read")
  (let [read-result (m/find-one coll id)
        response (assoc op
                   :type :ok
                   :value (:value read-result))
        _ (maplog [:stash :info] read-result "read response")]
    response))

(defn read-doc-wfam [op coll id]
  (maplog [:stash :info] op "readfam")
  (let [read-result (m/read-with-find-and-modify coll id)
        response (assoc op
                   :type :ok
                   :value (:value read-result))
        _ (maplog [:stash :info] read-result "readfam response")]
    response))

(defn update-doc [op coll id]
  (maplog [:stash :info] op "append")
  (let [res (m/update! coll id
                       {:$push {:value (:value op)}})]
    (info :write-result (pr-str res))
    (maplog [:stash :info] res "append response")
    (assert (:acknowledged? res))
    ; Note that modified-count could be zero, depending on the
    ; storage engine, if you perform a write the same as the
    ; current value.
    (assert (= 1 (:matched-count res)))
    (assoc op :type :ok)))

(defrecord Client [db-name
                   coll-name
                   id
                   read-concern
                   write-concern
                   read-with-find-and-modify
                   client
                   coll]
  client/Client
  (setup! [this test node]
     (info "setting up client on " node )
    (let [client (m/cluster-client test)
          coll   (-> client
                     (m/db db-name)
                     (m/collection coll-name)
                     (m/with-read-concern  read-concern)
                     (m/with-write-concern write-concern))]
      ; Create initial document
      (m/upsert! coll {:_id id, :value []})

      (assoc this :client client, :coll coll)))

  (invoke! [this test op]
    ; Reads are idempotent; we can treat their failure as an info.
    (with-errors op #{:read}
      (case (:f op)
        :read (if read-with-find-and-modify
                (read-doc-wfam op coll id)
                (read-doc op coll id))

        :add (update-doc op coll id)

        )))
  (teardown! [_ test]
    (.close ^java.io.Closeable client)))

(defn client
  "A client which implements a register on top of an entire document.

  Options:

    :read-concern  e.g. :majority
    :write-concern e.g. :majority"
  [opts]
  (Client. "jepsen"
           "cas"
           0
           (:read-concern (:mongodb opts))
           (:write-concern (:mongodb opts))
           (:read-with-find-and-modify (:mongodb opts))
           nil
           nil))

(defn check-sets
  "Given a set of :add operations followed by a final :read, verifies that
  every successfully added element is present in the read, and that the read
  contains only elements for which an add was attempted, and that all
  elements are unique."
  []
  (reify checker/Checker
    (check [this test model history opts]
      (let [attempts (->> history
                          (r/filter op/invoke?)
                          (r/filter #(= :add (:f %)))
                          (r/map :value)
                          (into #{}))
            adds (->> history
                      (r/filter op/ok?)
                      (r/filter #(= :add (:f %)))
                      (r/map :value)
                      (into #{}))
            fails (->> history
                       (r/filter op/fail?)
                       (r/filter #(= :add (:f %)))
                       (r/map :value)
                       (into #{}))
            unsure (->> history
                        (r/filter op/info?)
                        (r/filter #(= :add (:f %)))
                        (r/map :value)
                        (into #{}))
            final-read-l (->> history
                              (r/filter op/ok?)
                              (r/filter #(= :read (:f %)))
                              (r/map :value)
                              (reduce (fn [_ x] x) nil))]

        (if-not final-read-l
          {:valid? false
           :error  "Set was never read"})

        (let [final-read  (into #{} final-read-l)

              dups        (into [] (for [[id freq] (frequencies final-read-l) :when (> freq 1)] id))

              ;;The OK set is every read value which we added successfully
              ok          (set/intersection final-read adds)

              ;; Unexpected records are those we *never* attempted.
              unexpected  (set/difference final-read attempts)

              ;; Revived records are those that were reported as failed and still appear.
              revived  (set/intersection final-read fails)

              ;; Lost records are those we definitely added but weren't read
              lost        (set/difference adds final-read)

              ;; Recovered records are those where we didn't know if the add
              ;; succeeded or not, but we found them in the final set.
              recovered   (set/intersection final-read unsure)]

          {:valid?          (and (empty? lost) (empty? unexpected) (empty? dups) (empty? revived))
           :duplicates      dups
           :ok              (util/integer-interval-set-str ok)
           :lost            (util/integer-interval-set-str lost)
           :unexpected      (util/integer-interval-set-str unexpected)
           :recovered       (util/integer-interval-set-str recovered)
           :revived         (util/integer-interval-set-str revived)
           :ok-frac         (util/fraction (count ok) (count attempts))
           :revived-frac    (util/fraction (count revived) (count fails))
           :unexpected-frac (util/fraction (count unexpected) (count attempts))
           :lost-frac       (util/fraction (count lost) (count attempts))
           :recovered-frac  (util/fraction (count recovered) (count attempts))})))))


(defn test

  [opts]
  (test- "simple"
         (merge
           {:client      (client opts)
            :concurrency 100
            ;:generator   (gen/clients (gen/each (gen/seq [{:type :invoke, :f :add, :value :a}])))
            :generator   (gen/phases
                           (->> (range)
                                (map (partial array-map
                                              :type :invoke
                                              :f :add
                                              :value))
                                gen/seq
                                (gen/stagger 1)
                                (gen/time-limit (:time-limit opts))
                                gen/clients)
                           (->> {:type :invoke, :f :read, :value nil}
                                gen/once
                                gen/clients))
            :checker     (checker/compose
                           {:perf-dump (perf-dump)
                            ;:latency-graph (checker/latency-graph)
                            ;:rate-graph    (checker/rate-graph)
                            :details   (check-sets)})
            }
           opts)))
