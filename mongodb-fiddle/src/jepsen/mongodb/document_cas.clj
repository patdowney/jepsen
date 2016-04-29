(ns jepsen.mongodb.document-cas
  "Compare-and-set against a single document."
  (:refer-clojure :exclude [test])
  (:require [clojure [pprint :refer :all]
                     [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [debug info warn]]
            [jepsen [core      :as jepsen]
                    [util      :as util :refer [meh timeout]]
                    [control   :as c :refer [|]]
                    [client    :as client]
                    [checker   :as checker]
                    [independent :as independent]
                    [generator :as gen]
                    [nemesis   :as nemesis]
                    [store     :as store]
                    [report    :as report]
                    [tests     :as tests]]
            [jepsen.control [net :as net]
                            [util :as net/util]]
            [jepsen.os.debian :as debian]
            [jepsen.checker.timeline :as timeline]
            [knossos.core :as knossos]
            [knossos.model :as model]
            [jepsen.mongodb.core :refer :all]
            [jepsen.mongodb.mongo :as m])
  (:import (clojure.lang ExceptionInfo)))

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
    (let [client (m/cluster-client test)
          coll   (-> client
                     (m/db db-name)
                     (m/collection coll-name)
                     (m/with-read-concern  read-concern)
                     (m/with-write-concern write-concern))]
      ; Create document
      (m/upsert! coll {:_id id, :value nil})

      (assoc this :client client, :coll coll)))

  (invoke! [this test op]
    ; Reads are idempotent; we can treat their failure as an info.
    (with-errors op #{:read}
      (case (:f op)
        :read (let [res
                    ; Normal read
                    (m/find-one coll id)]
                (assoc op
                  :type :ok
                  :value (:value res)))

        :write (let [res (m/replace! coll {:_id id, :value op})]
                 (info :write-result (pr-str res))
                 (assert (:acknowledged? res))
                 ; Note that modified-count could be zero, depending on the
                 ; storage engine, if you perform a write the same as the
                 ; current value.
                 (assert (= 1 (:matched-count res)))
                 (assoc op :type :ok))

        :cas (let [[value value'] (:value op)
                   res (m/cas! coll
                               {:_id id, :value value}
                               {:_id id, :value value'})]
               ; Check how many documents we actually modified.
               (cond
                 (not (:acknowledged? res)) (assoc op :type :info, :error res)
                 (= 0 (:matched-count res)) (assoc op :type :fail)
                 (= 1 (:matched-count res)) (assoc op :type :ok)
                 true (assoc op :type :info
                                :error (str "CAS: matched too many docs! "
                                            res)))))))
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
           (:read-concern opts)
           (:write-concern opts)
           (:read-with-find-and-modify opts)
           nil
           nil))

; Generators
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn generator
  []
  (gen/mix [w r cas]))

(defn test
  "Document-level compare and set. Options are also passed through to
  core/test-.

  Special options:

    :write-concern              Keyword for write concern level
    :read-concern               Keyword for read concern level
    :read-with-find-and-modify  Use findAndModify to ensure read safety
    :time-limit                 How long do we run the test for?"
  [opts]
  (test- (str "doc cas"
              " r:" (name (:read-concern opts))
              " w:" (name (:write-concern opts)))
         (merge
           {:client       (client opts)
            :concurrency  10
            :generator (std-gen (gen/reserve 5 (gen/mix [w cas cas]) r))}
           opts)))
