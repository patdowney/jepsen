(ns jepsen.mongodb.core
  (:require [clojure [pprint :refer :all]
             [string :as str]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer [debug info warn trace spy]]
            [clojure.walk :as walk]
            [jepsen [core :as jepsen]
             [db :as db]
             [util :as util :refer [meh timeout]]
             [control :as c :refer [|]]
             [client :as client]
             [checker :as checker]
             [generator :as gen]
             [nemesis :as nemesis]
             [store :as store]
             [report :as report]
             [tests :as tests]]
            [jepsen.net :refer [iptables-old]]
            [jepsen.control :refer [su]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as net]
            [jepsen.os.debian :as debian]
            [jepsen.mongodb.mongo :as m]
            [jepsen.mongodb.mongod :as mongod]
            [jepsen.mongodb.null_os :as null_os]
            [jepsen.mongodb.cluster :as cluster]
            [knossos [core :as knossos]
             [model :as model]]
            [cheshire.core :as cheshire]
)
  (:import (clojure.lang ExceptionInfo)
           (jepsen.checker Checker)
           (com.mongodb MongoCommandException)))






