(ns jepsen.mongodb.tests.hashed-append
  "append integers to an array in a set of documents via hash key"
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer [debug info warn spy]]
            [clojure.core.reducers :as r]
            [jepsen
             [util :as jutil :refer [meh timeout]]
             [client :as client]
             [checker :as checker]
             [generator :as gen]]
            [jepsen.mongodb.reports :as reports]
            [jepsen.mongodb.util :as util]
            [jepsen.mongodb.base-tests :refer [test-]]
            [knossos.op :as op]
            [jepsen.mongodb.mongo :as m]
            [clojure.set :as set])
  (:import (clojure.lang  Murmur3)))

(defn hashfn [buckets ^Long value]
  (mod (Murmur3/hashLong value) buckets))

(defn read-results [op coll buckets]
  (let [read-results (map (partial m/find-one coll) (range buckets))
        value (mapcat (comp seq :value) read-results)]
    (assoc op
      :type :ok
      :value value)))

(defn read-results-wfam [op coll buckets]
  (let [read-results (map (partial m/read-with-find-and-modify coll) (range buckets))
        value (mapcat (comp seq :value) read-results)]
    (assoc op
      :type :ok
      :value value)))

(defn add-int-to-bucket [op coll id]
  (let [res (m/update! coll id
                       {:$push {:value (:value op)}})]
    (info :write-result (pr-str res))
    (assert (:acknowledged? res))
    ; Note that modified-count could be zero, depending on the
    ; storage engine, if you perform a write the same as the
    ; current value.
    (assert (= 1 (:matched-count res)))
    (assoc op :type :ok)))

(defrecord Client [db-name
                   coll-name
                   read-concern
                   write-concern
                   read-with-find-and-modify
                   buckets
                   client
                   coll
                   hasher]
  client/Client
  (setup! [this test node]
     (info "setting up client on " node )
    (let [client (m/cluster-client test)
          coll   (-> client
                     (m/db db-name)
                     (m/collection coll-name)
                     (m/with-read-concern  read-concern)
                     (m/with-write-concern write-concern))
          hasher (partial hashfn buckets)]
      ; Create initial document
      (doseq [id (range buckets)]
        (m/upsert! coll {:_id id, :value []}))

      (assoc this :client client, :coll coll, :buckets buckets, :hasher hasher)))

  (invoke! [this test op]
    ; Reads and adds are idempotent; we can treat their failure as an info.
    (util/with-timing-logs op
      (util/with-errors op #{:read}
        (case (:f op)
          :read (if read-with-find-and-modify
                  (read-results-wfam op coll buckets)
                  (read-results op coll buckets))

          :add (add-int-to-bucket op coll (hasher (:value op)))

          ))))
  (teardown! [_ test]
    (.close ^java.io.Closeable client)))



(defn client
  "A client which implements a set of arrays in buckets

  Options:

    :read-concern  e.g. :majority
    :write-concern e.g. :majority"
  [opts buckets]
  (Client. "jepsen"
           "hashed-append"
           (:read-concern (:mongodb opts))
           (:write-concern (:mongodb opts))
           (:read-with-find-and-modify (:mongodb opts))
           buckets
           nil
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
              recovered   (set/intersection final-read unsure)

              ;; unsure lost are those which we were unsure about and never actually were written
              unsure-lost (set/difference unsure final-read)]

          {:valid?          (and (empty? lost) (empty? unexpected) (empty? dups) (empty? revived))
           :duplicates      dups
           :ok              (jutil/integer-interval-set-str ok)
           :lost            (jutil/integer-interval-set-str lost)
           :unsure-lost     (jutil/integer-interval-set-str unsure-lost)
           :unexpected      (jutil/integer-interval-set-str unexpected)
           :recovered       (jutil/integer-interval-set-str recovered)
           :revived         (jutil/integer-interval-set-str revived)
           :ok-frac         (jutil/fraction (count ok) (count attempts))
           :revived-frac    (jutil/fraction (count revived) (count fails))
           :unexpected-frac (jutil/fraction (count unexpected) (count attempts))
           :lost-frac       (jutil/fraction (count lost) (count attempts))
           :unsure-lost-frac (jutil/fraction (count unsure-lost) (count attempts))
           :recovered-frac  (jutil/fraction (count recovered) (count attempts))})))))


(defn infinite-adds []
  (map (partial array-map
                :type :invoke
                :f :add
                :value)
       (range)))

(def default-buckets 10)

(defn append-ints-test
  [opts]
  (test- "append-ints"
         (merge
           {:client      (client opts (or (:buckets opts) default-buckets))
            :generator   (gen/phases
                           (->> (infinite-adds)
                                gen/seq
                                (gen/stagger 1)
                                (gen/nemesis
                                  (gen/seq (cycle [(gen/sleep (:nemesis-delay opts))
                                                   {:type :info :f :start}
                                                   (gen/sleep (:nemesis-duration opts))
                                                   {:type :info :f :stop}])))
                                (gen/time-limit (:time-limit opts)))
                           (->> {:type :invoke, :f :read, :value nil}
                                gen/once
                                gen/clients))
            :checker     (checker/compose
                           {:perf-dump (reports/perf-dump)
                            :details   (check-sets)})
            }
           opts)))

