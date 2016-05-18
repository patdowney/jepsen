(ns jepsen.mongodb.tests.synthetic-load
  "load data in a vaguely realistic way"
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
            [clojure.set :as set]
            [faker.name :as fname]
            [faker.address :as faddress]
            [faker.phone-number :as fphone]
            [faker.company :as fcompany]
            [faker.internet :as finternet]))

(comment "going to build two pseudo-doc structures in two collections - a Person and their Things, where Thing has a _personId field"
         "Faker is used to make sure there is fake data that's not too painful"
         "Person.email is an extra unique key just to check inserts"

         "later on might play with more complex structures - this is a skeleton to work with"
  )

(def used-emails (atom #{}))

(defn unique-email []
  (let [email (first (remove @used-emails (finternet/emails)))
        _ (swap! used-emails conj email)]
    email))

(defn random-person [id]
  {:_id        id
   :email      (unique-email)
   :first-name (fname/first-name)
   :last-name  (fname/last-name)
   :address    {:street   (faddress/street-address)
                :county   (faddress/uk-county)
                :postcode (faddress/uk-postcode)}
   :phone      {:primary (first (fphone/phone-numbers))
                :mobile (first (fphone/phone-numbers))}})

(defn add-person [op coll person]
  (let [res (m/upsert! coll person)]
    (info :write-result (pr-str res))
    (assert (:acknowledged? res))
    ; Note that modified-count could be zero, depending on the
    ; storage engine, if you perform a write the same as the
    ; current value.
    (assert (not (nil? (:upserted-id res))))                ; should always be an insert not an update
    (assoc op :type :ok)))

(defrecord Client [db-name
                   coll-name
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

      (assoc this :client client, :coll coll)))

  (invoke! [this test op]
    ; Reads and adds are idempotent; we can treat their failure as an info.
    (util/with-timing-logs op
      (util/with-errors op #{:read}
        (case (:f op)
          :add (add-person op coll (:value op))
          ))))
  (teardown! [_ test]
    (.close ^java.io.Closeable client)))

(defn client
  "A client which implements a bunch of stuff

  Options:

    :read-concern  e.g. :majority
    :write-concern e.g. :majority"
  [opts]
  (Client. "jepsen"
           "people"
           (:read-concern (:mongodb opts))
           (:write-concern (:mongodb opts))
           (:read-with-find-and-modify (:mongodb opts))
           nil
           nil))

(defn infinite-adds []
  (map (fn [id] {:type :invoke
                 :f    :add
                 :value (random-person id)})
       (range)))

(defn people-test
  [opts]
  (test- "people-test"
         (merge
           {:client      (client opts)
            :generator   (gen/phases
                           (->> (infinite-adds)
                                gen/seq
                                (gen/stagger (:test-delay-secs opts))
                                (gen/nemesis
                                  (gen/seq (cycle [(gen/sleep (:nemesis-delay opts))
                                                   {:type :info :f :start}
                                                   (gen/sleep (:nemesis-duration opts))
                                                   {:type :info :f :stop}])))
                                (gen/time-limit (:time-limit opts)))
                           ; any read phase goes here
                           )
            :checker     (checker/compose
                           {:perf-dump (reports/perf-dump)
                            ; any checker goes here
                            })
            }
           opts)))

