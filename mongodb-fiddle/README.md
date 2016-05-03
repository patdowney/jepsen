# Jepsen MongoDB tests

Evaluates single-document compare-and-set against a MongoDB cluster.

## Examples

```sh
# Short test with defaults taken from config.edn
lein run config.edn

```

## Building and running as a single jar

```sh
lein uberjar
java -jar target/jepsen.mongodb-0.2.0-SNAPSHOT-standalone.jar config.edn ...
```

## Full usage

usage is all config file driven now.

## TODO

Would like to use schema to validate configs, help avoid typos

## License

Copyright © 2015, 2016 Kyle Kingsbury & Jepsen, LLC

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
