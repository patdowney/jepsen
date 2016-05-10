(defproject jepsen.mongodb-fiddle "0.2.0-SNAPSHOT"
  :description "Jepsen MongoDB tests - fiddling with options"
  :url "https://github.com/aphyr/jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/tools.logging "0.3.1"]
                 [ch.qos.logback/logback-classic "1.1.7"]
                 [jepsen "0.1.1-SNAPSHOT" :exclusions [org.slf4j/slf4j-log4j12 ]]
                 [org.mongodb/mongodb-driver "3.2.2"]
                 [org.codehaus.groovy/groovy-all "2.4.6"]
                 [aero "1.0.0-beta2"]
                 [cheshire "5.6.1"]]
  :main jepsen.mongodb.runner
  :aot [jepsen.mongodb.runner
        clojure.tools.logging.impl])
