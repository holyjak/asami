(ns asami.api-test
  "Tests the public query functionality"
  (:require [asami.core :as a :refer [q show-plan create-database connect db transact
                                      entity as-of since import-data export-data delete-database]]
            [asami.index :as i]
            [asami.graph :as graph]
            [asami.multi-graph :as m]
            [asami.internal :refer [now]]
            [zuko.logging :as log]
            [schema.core :as s]
            #?(:clj  [clojure.test :refer [is use-fixtures testing]]
               :cljs [clojure.test :refer-macros [is run-tests use-fixtures testing]])
            #?(:clj  [schema.test :as st :refer [deftest]]
               :cljs [schema.test :as st :refer-macros [deftest]]))
  #?(:clj (:import [clojure.lang ExceptionInfo]
                   [java.time Instant])))

(def ^:dynamic *conn* nil)

(use-fixtures :each (fn init-test-conn+cleanup-all [test]
                      (binding [*conn* (a/connect "asami:mem://apitest")]
                        (try (test)
                             (finally
                               (a/delete-database "asami:mem://apitest")
                               (doseq [url (keys @a/connections)]
                                 (a/delete-database url)))))))

(defn other-now
  "Create a different kind of instant object on the JVM"
  []
  #?(:clj (Instant/now) :cljs (now)))

(deftest test-create
  (let [c1 (create-database "asami:mem://babaco")
        c2 (create-database "asami:mem://babaco")
        c3 (create-database "asami:mem://kumquat")
        c4 (create-database "asami:mem://kumquat")
        c5 (create-database "asami:multi://kumquat")]
    (is c1)
    (is (not c2))
    (is c3)
    (is (not c4))
    (is c5)
    (is (thrown-with-msg? ExceptionInfo #"Unknown graph URI schema"
                          (create-database "asami:other://kumquat")))))

(deftest test-connect
  (let [c (connect "asami:mem://apple")
        cm (connect "asami:multi://banana")]
    (is (instance? asami.index.GraphIndexed (:graph (:db @(:state c)))))
    (is (= "apple" (:name c)))
    (is (instance? asami.multi_graph.MultiGraph (:graph (:db @(:state cm)))))
    (is (= "banana" (:name cm)))))

(deftest test-input-limit
  (let [c *conn*
        maksim {:db/id -1
                :name  "Maksim"
                :age   45
                :wife {:db/id -2}
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :name  "Anna"
                :age   31
                :husband {:db/id -1}
                :aka   ["Anitzka"]}
        {:keys [tx-data]} @(transact c {:tx-data [maksim anna]})
        c2 (connect "asami:mem://limit2")
        {tx-data2 :tx-data} @(transact c2 {:tx-data [maksim anna] :input-limit 15})]
    (is (= 21 (count tx-data)))
    (is (= 21 (q '[:find (count *) . :where [?s ?p ?o]] c)))
    (is (= 13 (count tx-data2)))
    (is (= 13 (q '[:find (count *) . :where [?s ?p ?o]] c2)))))

(deftest load-data
  (let [c (connect "asami:mem://test1")
        r (transact c {:tx-data [{:db/ident "bobid"
                                  :person/name "Bob"
                                  :person/spouse "aliceid"}
                                 {:db/ident "aliceid"
                                  :person/name "Alice"
                                  :person/spouse "bobid"}]})]
    (= (->> (:tx-data @r)
            (filter #(= :db/ident (second %)))
            (map #(nth % 2))
            set)
       #{"bobid" "aliceid"}))

  (let [c (connect "asami:mem://test2")
        r (transact c {:tx-data [[:db/add :mem/node-1 :property "value"]
                                 [:db/add :mem/node-2 :property "other"]]})]

    (is (= 2 (count (:tx-data @r)))))

  (let [c (connect "asami:mem://test2")
        maksim {:db/id -1
                :name  "Maksim"
                :age   45
                :wife {:db/id -2}
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :name  "Anna"
                :age   31
                :husband {:db/id -1}
                :aka   ["Anitzka"]}
        {:keys [tempids tx-data] :as r} @(transact c [maksim anna])
        one (tempids -1)
        two (tempids -2)]
    (is (= 21 (count tx-data)))
    (is (= #{[one :db/ident one]
             [one :a/entity true]
             [one :name "Maksim"]
             [one :age 45]
             [one :wife two]}
           (->> tx-data
                (filter #(= one (first %)))
                (remove #(= :aka (second %)))
                (remove #(= :a/owns (second %)))
                (map (partial take 3))
                set)))
    (is (= #{[two :db/ident two]
             [two :a/entity true]
             [two :name "Anna"]
             [two :age 31]
             [two :husband one]}
           (->> tx-data
                (filter #(= two (first %)))
                (remove #(= :aka (second %)))
                (remove #(= :a/owns (second %)))
                (map (partial take 3))
                set))))

  (let [c (connect "asami:mem://test3")
        maksim {:db/ident "maksim"
                :name  "Maksim"
                :age   45
                :wife {:db/ident "anna"}
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/ident "anna"
                :name  "Anna"
                :age   31
                :husband {:db/ident "maksim"}
                :aka   ["Anitzka"]}
        {:keys [tempids tx-data] :as r} @(transact c [maksim anna])
        one (tempids "maksim")
        two (tempids "anna")]
    (is (= 21 (count tx-data)))
    (is (= #{[one :db/ident "maksim"]
             [one :a/entity true]
             [one :name "Maksim"]
             [one :age 45]
             [one :wife two]}
           (->> tx-data
                (filter #(= one (first %)))
                (remove #(= :aka (second %)))
                (remove #(= :a/owns (second %)))
                (map (partial take 3))
                set)))
    (is (= #{[two :db/ident "anna"]
             [two :a/entity true]
             [two :name "Anna"]
             [two :age 31]
             [two :husband one]}
           (->> tx-data
                (filter #(= two (first %)))
                (remove #(= :aka (second %)))
                (remove #(= :a/owns (second %)))
                (map (partial take 3))
                set))))

  (let [c (connect "asami:mem://test4")
        r (transact c {:tx-triples [[:mem/node-1 :property "value"]
                                    [:mem/node-2 :property "other"]]})]

    (is (= 2 (count (:tx-data @r))))))

(deftest test-retractions
  (let [c *conn*
        {d :db-after} @(transact c {:tx-data [{:db/ident "bobid"
                                               :person/name "Bob"
                                               :person/spouse "aliceid"}
                                              {:db/ident "aliceid"
                                               :person/name "Alice"
                                               :person/spouse "bobid"}]})
        bob (entity d "bobid")
        alice (entity d "aliceid")]
    (is (= {:person/name "Bob" :person/spouse "aliceid"} bob))
    (is (= {:person/name "Alice" :person/spouse "bobid"} alice))
    (let [id (q '[:find ?id . :where [?id :db/ident "bobid"]] d)
          {d :db-after} @(transact c {:tx-data [[:db/retract id :person/spouse "aliceid"]]})
          bob (entity d "bobid")
          alice (entity d "aliceid")]
      (is (= {:person/name "Bob"} bob))
      (is (= {:person/name "Alice" :person/spouse "bobid"} alice))))

  (let [c (connect "asami:mem://testr2")
        r @(transact c {:tx-data [[:db/add :mem/node-1 :property "value"]
                                  [:db/add :mem/node-2 :property "other"]]})]
    (is (= 2 (count (:tx-data r))))
    (is (= 2 (count (q '[:find ?e ?a ?v :where [?e ?a ?v]] (:db-after r)))))
    (let [r2 @(transact c {:tx-data [[:db/retract :mem/node-1 :property "value"]
                                     [:db/retract :mem/node-1 :property "missing"]]})]
      (is (= 1 (count (:tx-data r2))))
      (is (= [[:mem/node-2 :property "other"]]
             (q '[:find ?e ?a ?v :where [?e ?a ?v]] (:db-after r2)))))))

(deftest test-assertions-retractions-using-lookup-refs
  (let [c *conn*
        {d :db-after} @(transact c {:tx-data [{:db/id -1
                                               :db/ident "bobid"
                                               :person/name "Bob"
                                               :person/age 42}
                                              {:db/ident "bettyid"
                                               :person/name "Betty"
                                               :person/age 43
                                               :person/friend [:db/id -1]}]})
        bob (entity d "bobid")
        betty (entity d "bettyid" true)]
    (is (= {:person/name "Bob" :person/age 42} bob))
    (is (= {:person/name "Betty" :person/age 43 :person/friend bob} betty))
    (let [{d :db-after} @(transact c {:tx-data [[:db/add [:db/ident "bobid"] :person/street "First Street"]
                                                [:db/retract [:db/ident "bobid"] :person/age 42]
                                                [:db/retract [:db/ident "bettyid"] :person/friend [:db/ident "bobid"]]]})
          bob (entity d "bobid")
          betty (entity d "bettyid")]
      (is (= {:person/street "First Street" :person/name "Bob"} bob))
      (is (= {:person/name "Betty" :person/age 43} betty)))))

(deftest test-db-add-with-lookup-ref-creates-entity
  ;; Notice the entities are provided in the correct order - dependants after those they depend on (refer to)
  (let [{d :db-after} @(transact *conn* {:tx-data [[:db/add [:id "bobid"] :id "bobid"]
                                                   [:db/add [:id "bobid"] :person/name "Bob"]
                                                   [:db/add [:id "bobid"] :person/age 42]

                                                   {:id "maryid" :person/name "Mary", :person/age 18}

                                                   [:db/add [:db/ident "bettyid"] :db/ident "bettyid"]
                                                   [:db/add [:db/ident "bettyid"] :person/name "Betty"]
                                                   [:db/add [:db/ident "bettyid"] :person/age 43]
                                                   [:db/add [:db/ident "bettyid"] :person/friend [:id "bobid"]]
                                                   [:db/add [:db/ident "bettyid"] :person/bff [:id "maryid"]]]})
        bob (entity d "bobid")
        mary (entity d "maryid")
        betty (entity d "bettyid" true)
        bob-node-ref (a/q '[:find ?n . :where [?n :id "bobid"]] d)
        betty-node-ref (a/q '[:find ?n . :where [?n :db/ident "bettyid"]] d)]
    (is (keyword? bob-node-ref) "We should end up with a proper node id and not `[:id \"bobid\"]` used as the node id")
    (is (keyword? betty-node-ref) "We should end up with a proper node id and not `[:db/ident \"bettyid\"]` used as the node id")
    (is (= {:id "bobid" :person/name "Bob" :person/age 42} bob))
    ;; FIXME The problem is that the ID map becomes `{[:id bobid] :a/node-28618, maryid :a/node-28619}` - ie mary is in wrong form ?!
    (is (= {:person/name "Betty" :person/age 43 :person/friend bob, :person/bff mary} betty))))

(deftest test-entity
  (let [c *conn*
        maksim {:db/id -1
                :db/ident :maksim
                :id    "MakOvS"
                :name  "Maksim"
                :age   45
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :db/ident :anna
                :name  "Anna"
                :age   31
                :husband {:db/id -1}
                :aka   ["Anitzka"]}
        pete   {:db/id -3
                :db/ident "pete"
                :name  #{"Peter" "Pete" "Petrov"}
                :age   25
                :aka ["Peter the Great" #{"Petey" "Petie"}]
                }
        {:keys [tempids tx-data] :as r} @(transact c [maksim anna pete])
        one (tempids -1)
        two (tempids -2)
        three (tempids -3)
        d (db c)]
    (is (= {:id    "MakOvS"
            :name  "Maksim"
            :age   45
            :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
           (entity d one)))
    (is (= {:id    "MakOvS"
            :name  "Maksim"
            :age   45
            :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
           (entity d :maksim)))
    (is (= {:id    "MakOvS"
            :name  "Maksim"
            :age   45
            :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
           (entity d "MakOvS")))
    (is (= {:name  "Anna"
            :age   31
            :husband {:db/ident :maksim}
            :aka   ["Anitzka"]}
           (entity d :anna)))
    (is (= {:name  #{"Peter" "Pete" "Petrov"}
            :age   25
            :aka ["Peter the Great" ["Petey" "Petie"]]}
           (entity d three)))))

(deftest test-entity-arrays
  (let [c *conn*
        data {:db/id -1
              :db/ident :home
              :name  "Home"
              :address nil
              :rooms   ["Room 1" nil "Room 2" nil "Room 3"]}
        {:keys [tempids tx-data] :as r} @(transact c [data])
        one (tempids -1)
        d (db c)]
    ;; nil is contained twice, so 19 statements, rather than the 20 inserted
    (is (= 19 (count tx-data)))
    (is (= 2 (count (filter #(and (= :a/first (nth % 1)) (= :a/nil (nth % 2))) tx-data))))
    (is (= {:name  "Home"
            :address nil
            :rooms   ["Room 1" nil "Room 2" nil "Room 3"]}
           (entity d one)))))

(deftest test-entity-nested
  (let [c *conn*
        d1 {:db/id -1
            :db/ident "nested-object"
            :name "nested"}
        d2 {:db/id -2
            :name "nested2"}
        data {:db/id -3
              :db/ident "top"
              :name  "Main"
              :sub   {:db/ident "nested-object"}}
        data2 {:db/id -4
              :db/ident "top2"
              :name  "Main2"
              :sub   {:db/id -2}}
        r0 @(transact c [d1])
        {:keys [tempids tx-data] :as r} @(transact c [d2 data data2])
        dx (tempids -2)
        one (tempids -3)
        two (tempids -4)
        d (db c)]
    (is (= {:name  "Main"
            :sub   {:db/ident "nested-object"}}
           (entity d one)))
    (is (= {:name  "Main"
            :sub   {:name "nested"}}
           (entity d one true)))
    (is (= {:name  "Main2"
            :sub   {:db/ident dx}}
           (entity d two)))
    (is (= {:name  "Main2"
            :sub   {:name "nested2"}}
           (entity d two true)))))

(defn sleep [msec]
  #?(:clj
     (Thread/sleep msec)
     :cljs
     (let [deadline (+ msec (.getTime (js/Date.)))]
       (while (> deadline (.getTime (js/Date.)))))))

(deftest test-as
  (let [t0 (now)
        _ (sleep 100)
        c (connect "asami:mem://test5")
        _ (sleep 100)
        t1 (other-now)
        maksim {:db/id -1
                :db/ident :maksim
                :name  "Maksim"
                :age   45
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :db/ident :anna
                :name  "Anna"
                :age   31
                :husband {:db/ident :maksim}
                :aka   ["Anitzka"]}
        _ (sleep 100)
        _ @(transact c [maksim])
        _ (sleep 100)
        t2 (now)
        _ (sleep 100)
        _ @(transact c [anna])
        _ (sleep 100)
        t3 (other-now)
        latest-db (db c)
        db0 (as-of latest-db t0)  ;; before
        db1 (as-of latest-db t1)  ;; empty
        db2 (as-of latest-db t2)  ;; after first tx
        db3 (as-of latest-db t3)  ;; after second tx
        db0' (as-of latest-db 0)  ;; empty
        db1' (as-of latest-db 1)  ;; after first tx
        db2' (as-of latest-db 2)  ;; after second tx
        db3' (as-of latest-db 3)  ;; still after second tx

        db0* (since latest-db t0)  ;; before
        db1* (since latest-db t1)  ;; after first tx
        db2* (since latest-db t2)  ;; after second tx
        db3* (since latest-db t3)  ;; well after second tx
        db0*' (since latest-db 0)  ;; after first tx
        db1*' (since latest-db 1)  ;; after second tx
        db2*' (since latest-db 2)  ;; still after second tx
        db3*' (since latest-db 3)] ;; still after second tx
    (is (= db0 db0'))
    (is (= db0 db1))
    (is (= db2 db1'))
    (is (= db3 db2'))
    (is (= db2' db3'))
    (is (= db0 db0*))
    (is (= db2 db1*))
    (is (= db3 db2*))
    (is (= nil db3*))
    (is (= db2 db0*'))
    (is (= db3 db1*'))
    (is (= nil db2*'))
    (is (= nil db3*'))
    (is (= db3 latest-db))
    (is (= (set (q '[:find ?name :where [?e :name ?name]] db0))
           #{}))
    (is (= (set (q '[:find ?name :where [?e :name ?name]] db1))
           #{}))
    (is (= (set (q '[:find ?name :where [?e :name ?name]] db2))
           #{["Maksim"]}))
    (is (= (set (q '[:find ?name :where [?e :name ?name]] db3))
           #{["Maksim"] ["Anna"]}))
    (is (= (set (q '[:find [?a ...] :where [?e ?a ?v]] db2))
           #{:db/ident :name :age :aka :a/entity :a/contains :a/first :a/rest :a/owns}))))

(deftest test-id-update
  (let [c *conn*
        maksim {:db/id -1
                :id :maksim
                :name  "Maksim"
                :age   45
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :id :anna
                :name  "Anna"
                :age   31
                :husband {:db/id -1}
                :aka   ["Anitzka"]}
        {db1 :db-after} @(transact c [maksim anna])
        anne   {:id :anna
                :name' "Anne"
                :age   32}
        {db2 :db-after} @(transact c [anne])
        maks   {:id :maksim
                :aka' ["Maks Otto von Stirlitz"]}
        {db3 :db-after} @(transact c [maks])]
    (is (= (set (q '[:find ?aka :where [?e :name "Maksim"] [?e :aka ?a] [?a :a/contains ?aka]] db1))
           #{["Maks Otto von Stirlitz"] ["Jack Ryan"]}))
    (is (= (set (q '[:find ?age :where [?e :id :anna] [?e :age ?age]] db1))
           #{[31]}))
    (is (= (set (q '[:find ?name :where [?e :id :anna] [?e :name ?name]] db1))
           #{["Anna"]}))
    (is (= (set (q '[:find ?age :where [?e :id :anna] [?e :age ?age]] db2))
           #{[31] [32]}))
    (is (= (set (q '[:find ?name :where [?e :id :anna] [?e :name ?name]] db2))
           #{["Anne"]}))
    (is (= (set (q '[:find ?aka :where [?e :name "Maksim"] [?e :aka ?a] [?a :a/contains ?aka]] db3))
           #{["Maks Otto von Stirlitz"]}))
    (is (= (entity db3 :maksim)
           {:id :maksim :name "Maksim" :age 45 :aka ["Maks Otto von Stirlitz"]}))
    (is (= (entity db3 :anna)
           {:id :anna :name "Anne" :age #{31 32} :husband {:id :maksim} :aka ["Anitzka"]}))))

(deftest test-update
  (let [c *conn*
        maksim {:db/id -1
                :db/ident :maksim
                :name  "Maksim"
                :age   45
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :db/ident :anna
                :name  "Anna"
                :age   31
                :husband {:db/id -1}
                :aka   ["Anitzka"]}
        {db1 :db-after} @(transact c [maksim anna])
        anne   {:db/ident :anna
                :name' "Anne"
                :age   32}
        {db2 :db-after} @(transact c [anne])
        maks   {:db/ident :maksim
                :aka' ["Maks Otto von Stirlitz"]}
        {db3 :db-after} @(transact c [maks])]
    (is (= (set (q '[:find ?aka :where [?e :name "Maksim"] [?e :aka ?a] [?a :a/contains ?aka]] db1))
           #{["Maks Otto von Stirlitz"] ["Jack Ryan"]}))
    (is (= (set (q '[:find ?age :where [?e :db/ident :anna] [?e :age ?age]] db1))
           #{[31]}))
    (is (= (set (q '[:find ?name :where [?e :db/ident :anna] [?e :name ?name]] db1))
           #{["Anna"]}))
    (is (= (set (q '[:find ?age :where [?e :db/ident :anna] [?e :age ?age]] db2))
           #{[31] [32]}))
    (is (= (set (q '[:find ?name :where [?e :db/ident :anna] [?e :name ?name]] db2))
           #{["Anne"]}))
    (is (= (set (q '[:find ?aka :where [?e :name "Maksim"] [?e :aka ?a] [?a :a/contains ?aka]] db3))
           #{["Maks Otto von Stirlitz"]}))))

(deftest test-update-struct
  (let [c *conn*
        maksim {:db/id -1
                :db/ident [:maksim 45]
                :name  "Maksim"
                :age   45
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :db/ident :anna
                :name  "Anna"
                :age   31
                :husband {:db/id -1}
                :aka   ["Anitzka"]}
        {db1 :db-after} @(transact c [maksim anna])
        anne   {:db/ident :anna
                :name' "Anne"
                :age   32}
        {db2 :db-after} @(transact c [anne])
        maks   {:db/ident [:maksim 45]
                :aka' ["Maks Otto von Stirlitz"]}
        {db3 :db-after} @(transact c [maks])]
    (is (= (set (q '[:find ?aka :where [?e :name "Maksim"] [?e :aka ?a] [?a :a/contains ?aka]] db1))
           #{["Maks Otto von Stirlitz"] ["Jack Ryan"]}))
    (is (= (set (q '[:find ?aka :where [?e :db/ident [:maksim 45]] [?e :aka ?a] [?a :a/contains ?aka]] db1))
           #{["Maks Otto von Stirlitz"] ["Jack Ryan"]}))
    (is (= (set (q '[:find ?age :where [?e :db/ident :anna] [?e :age ?age]] db1))
           #{[31]}))
    (is (= (set (q '[:find ?name :where [?e :db/ident :anna] [?e :name ?name]] db1))
           #{["Anna"]}))
    (is (= (set (q '[:find ?age :where [?e :db/ident :anna] [?e :age ?age]] db2))
           #{[31] [32]}))
    (is (= (set (q '[:find ?name :where [?e :db/ident :anna] [?e :name ?name]] db2))
           #{["Anne"]}))
    (is (= (set (q '[:find ?aka :where [?e :db/ident [:maksim 45]] [?e :aka ?a] [?a :a/contains ?aka]] db3))
           #{["Maks Otto von Stirlitz"]}))))


(deftest test-append
  (let [c *conn*
        maksim {:db/id -1
                :db/ident :maksim
                :name  "Maksim"
                :age   45
                :aka   ["Maks Otto von Stirlitz", "Jack Ryan"]}
        anna   {:db/id -2
                :db/ident :anna
                :name  "Anna"
                :age   31
                :husband {:db/id -1}
                :aka   ["Anitzka" "Annie"]}
        {db1 :db-after :as tx1} @(transact c [maksim anna])
        anne   {:db/ident :anna
                :aka+ "Anne"
                :age   32
                :friend+ "Peter"}
        {db2 :db-after :as tx2} @(transact c [anne])]
    (is (= (set (q '[:find ?aka :where [?e :name "Anna"] [?e :aka ?a] [?a :a/contains ?aka]] db1))
           #{["Anitzka"] ["Annie"]}))
    (is (= (set (q '[:find ?age :where [?e :db/ident :anna] [?e :age ?age]] db1))
           #{[31]}))
    (is (= {:name "Anna" :age 31 :aka ["Anitzka" "Annie"] :husband {:db/ident :maksim}}
           (entity db1 :anna)))

    (is (= (set (q '[:find ?aka :where [?e :name "Anna"] [?e :aka ?a] [?a :a/contains ?aka]] db2))
           #{["Anitzka"] ["Annie"] ["Anne"]}))
    (is (= (set (q '[:find ?age :where [?e :db/ident :anna] [?e :age ?age]] db2))
           #{[31] [32]}))
    (is (= (set (q '[:find ?friend :where [?e :name "Anna"] [?e :friend ?f] [?f :a/contains ?friend]] db2))
           #{["Peter"]}))
    (is (= {:name "Anna" :age #{31 32} :aka ["Anitzka" "Annie" "Anne"] :friend ["Peter"] :husband {:db/ident :maksim}}
           (entity db2 :anna)))))

(deftest test-filter-function
  (testing "filter function is constructed properly"
    (let [conn (connect "asami:mem://test8")]
      (deref (transact conn {:tx-data [{:movie/title "Explorers"
                                        :movie/genre "adventure/comedy/family"
                                        :movie/release-year 1985}
                                       {:movie/title "Demolition Man"
                                        :movie/genre "action/sci-fi/thriller"
                                        :movie/release-year 1993}
                                       {:movie/title "Johnny Mnemonic"
                                        :movie/genre "cyber-punk/action"
                                        :movie/release-year 1995}
                                       {:movie/title "Toy Story"
                                        :movie/genre "animation/adventure"
                                        :movie/release-year 1995}]}))
      (is (= [["Explorers"]
              ["Toy Story"]]
             (q '{:find [?name]
                  :where [[?m :movie/title ?name]
                          [?m :movie/genre ?genre]
                          [(re-find #"comedy|animation" ?genre)]]}
                (db conn)))))))

(deftest test-aggregate
  (testing "Aggregate grouping is working as expected"
    (let [c (connect "asami:mem://test-aggregate")]
      @(transact c {:tx-data [{:name "Alison"
                               :address "1313 Mockingbird Lane"
                               :children [{:name "Andrew" :age 16} {:name "Boris" :age 14} {:name "Cassie" :age 12}]}
                              {:name "Beatrice"
                               :address "742 Evergreen Terrace"
                               :children [{:name "Allie" :age 13} {:name "Barry" :age 11}]}
                              {:name "Charlie"
                               :address "1313 Mockingbird Lane"
                               :children [{:name "Aaron" :age 16} {:name "Barbara" :age 6}]}]})
      (is (= (set (q '[:find ?address (count ?child)
                       :where
                       [?parent :address ?address]
                       [?parent :children ?children]
                       [?children :a/contains ?child]] (db c)))
             #{["1313 Mockingbird Lane" 5] ["742 Evergreen Terrace" 2]}))
      (let [grouped (q '[:find ?entity (count ?entity)
                         :where
                         [?entity :a/entity true]] (db c))]
        (is (= 3 (count grouped)))
        (is (every? #{1} (map second grouped)))))))

(def transitive-data
    [{:db/id -1 :name "Washington Monument"}
     {:db/id -2 :name "National Mall"}
     {:db/id -3 :name "Washington, DC"}
     {:db/id -4 :name "USA"}
     {:db/id -5 :name "Earth"}
     {:db/id -6 :name "Solar System"}
     {:db/id -7 :name "Orion-Cygnus Arm"}
     {:db/id -8 :name "Milky Way Galaxy"}
     [:db/add -1 :is-in -2]
     [:db/add -2 :is-in -3]
     [:db/add -3 :is-in -4]
     [:db/add -4 :is-in -5]
     [:db/add -5 :is-in -6]
     [:db/add -6 :is-in -7]
     [:db/add -7 :is-in -8]])

(deftest test-transitive
  (let [c *conn*
        tx (transact c {:tx-data transitive-data})
        d (:db-after @tx)]
    (is (=
         (q '{:find [[?name ...]]
              :where [[?e :name "Washington Monument"]
                      [?e :is-in ?e2]
                      [?e2 :name ?name]]} d)
         ["National Mall"]))
    (is (=
         (q '{:find [[?name ...]]
              :where [[?e :name "Washington Monument"]
                      [?e :is-in* ?e2]
                      [?e2 :name ?name]]} d)
         ["Washington Monument" "National Mall" "Washington, DC" "USA" "Earth" "Solar System" "Orion-Cygnus Arm" "Milky Way Galaxy"]))
    (is (=
         (q '{:find [[?name ...]]
              :where [[?e :name "Washington Monument"]
                      [?e :is-in+ ?e2]
                      [?e2 :name ?name]]} d)
         ["National Mall" "Washington, DC" "USA" "Earth" "Solar System" "Orion-Cygnus Arm" "Milky Way Galaxy"]))))

;; tests both the show-plan function and the options
(deftest test-plan
  (let [c *conn*
        {d :db-after :as tx} @(transact c {:tx-data transitive-data})
        p1 (show-plan '[:find [?name ...]
                         :where [?e :name "Washington Monument"]
                         [?e :is-in ?e2]
                         [?e2 :name ?name]] d)
        p2 (show-plan '[:find [?name ...]
                         :where [?e2 :name ?name]
                         [?e :is-in ?e2]
                         [?e :name "Washington Monument"]] d)
        p3 (show-plan '[:find [?name ...]
                         :where [?e2 :name ?name]
                         [?e :is-in ?e2]
                         [?e :name "Washington Monument"]] d :planner :user)
        p4 (show-plan '[:find (count ?name)
                         :where [?e2 :name ?name]
                         [?e :is-in ?e2]
                         [?e :name "Washington Monument"]] d)]
    (is (= p1 '{:plan [[?e :name "Washington Monument"]
                       [?e :is-in ?e2]
                       [?e2 :name ?name]]}))
    (is (= p2 '{:plan [[?e :name "Washington Monument"]
                       [?e :is-in ?e2]
                       [?e2 :name ?name]]}))
    (is (= p3 '{:plan [[?e2 :name ?name]
                       [?e :is-in ?e2]
                       [?e :name "Washington Monument"]]}))))

(deftest test-plan-with-opt
  (let [c *conn*
        {d :db-after :as tx} @(transact c {:tx-data [{:movie/title "Explorers"
                                                      :movie/genre "adventure/comedy/family"
                                                      :movie/release-year 1985}
                                                     {:movie/title "Demolition Man"
                                                      :movie/genre "action/sci-fi/thriller"
                                                      :movie/release-year 1993}
                                                     {:movie/title "Johnny Mnemonic"
                                                      :movie/genre "cyber-punk/action"
                                                      :movie/release-year 1995}
                                                     {:movie/title "Toy Story"
                                                      :movie/genre "animation/adventure/comedy"
                                                      :movie/release-year 1995
                                                      :movie/sequel "Toy Story 2"}
                                                     {:movie/title "Sense and Sensibility"
                                                      :movie/genre "drama/romance"
                                                      :movie/release-year 1995}]})
        p1 (show-plan '[:find ?name ?sequel
                        :where [?m :movie/title ?name]
                        [?m :movie/release-year 1995]
                        (optional [?m :movie/sequel ?sequel])] d)]
    (is (= p1 '{:plan ([?m :movie/release-year 1995] (optional [?m :movie/sequel ?sequel]) [?m :movie/title ?name])}))))

(def opt-data
  [{:db/ident "austen"
    :name "Austen, Jane"}
   {:author {:db/ident "austen"}
    :title "Pride and Prejudice"}
   {:author {:db/ident "austen"}
    :label "Emma"}
   {:author {:db/ident "austen"}
    :label "Sense and Sensibility"}
   {:db/ident "bronte"
    :name "Brontë, Charlotte"}
   {:author {:db/ident "bronte"}
    :title "Jane Eyre"}
   {:db/ident "shelley"
    :name "Shelley, Mary"}])

(deftest test-optional
  (let [c *conn*
        {d :db-after :as tx} @(transact c {:tx-data opt-data})
        rx (q '[:find ?name ?t
                :where [?a :name ?name]
                (and [?book :author ?a] [?book :title ?t])] d)
        ry (q '[:find ?name ?t
                :where [?a :name ?name]
                (and [?book :author ?a] (or [?book :title ?t] [?book :label ?t]))] d)
        r1 (q '[:find ?name ?t
                :where [?a :name ?name]
                (optional [?book :author ?a] [?book :title ?t])] d)
        r2 (q '[:find ?name ?t
                :where [?a :name ?name]
                (optional [?book :author ?a] (or [?book :title ?t] [?book :label ?t]))] d)]
    (is (= #{["Austen, Jane" "Pride and Prejudice"]
             ["Brontë, Charlotte" "Jane Eyre"]}
           (set rx)))
    (is (= #{["Austen, Jane" "Pride and Prejudice"]
             ["Austen, Jane" "Sense and Sensibility"]
             ["Austen, Jane" "Emma"]
             ["Brontë, Charlotte" "Jane Eyre"]}
           (set ry)))
    (is (= #{["Austen, Jane" "Pride and Prejudice"]
             ["Brontë, Charlotte" "Jane Eyre"]
             ["Shelley, Mary" nil]}
           (set r1)))
    (is (= #{["Austen, Jane" "Pride and Prejudice"]
             ["Austen, Jane" "Sense and Sensibility"]
             ["Austen, Jane" "Emma"]
             ["Brontë, Charlotte" "Jane Eyre"]
             ["Shelley, Mary" nil]}
           (set r2)))))

(deftest test-explicit-default-graph
  (testing "explicity default graph"
    (let [conn (connect "asami:mem://test11")]
      (deref (transact conn {:tx-data [{:movie/title "Explorers"
                                        :movie/genre "adventure/comedy/family"
                                        :movie/release-year 1985}]}))
      (is (= "Explorers"
             (q '{:find [?name .]
                  :in [$]
                  :where [[?m :movie/title ?name]
                          [?m :movie/release-year 1985]]}
                  (db conn)))))))

(def raw-data
  [[:a/node-10511 :db/ident "charles"]
   [:a/node-10511 :a/entity true]
   [:a/node-10511 :name "Charles"]
   [:a/node-10511 :home :a/node-10512]
   [:a/node-10512 :db/ident "scarborough"]
   [:a/node-10512 :town "Scarborough"]
   [:a/node-10512 :county "Yorkshire"]
   [:a/node-10513 :db/ident "jane"]
   [:a/node-10513 :a/entity true]
   [:a/node-10513 :name "Jane"]
   [:a/node-10513 :home :a/node-10512]])

(def io-entities
  [{:db/ident "charles"
    :name "Charles"
    :home {:db/ident "scarborough"
           :town "Scarborough"
           :county "Yorkshire"}}
   {:db/ident "jane"
    :name "Jane"
    :home {:db/ident "scarborough"}}])

(defn node [id] (asami.graph.InternalNode. id))

(deftest test-import-export
  (testing "Loading raw data"
    (let [conn (connect "asami:mem://test12")
          {d :db-after} @(import-data conn raw-data)]
      (is (= #{[:a/node-10511 "Charles"]
               [:a/node-10513 "Jane"]}
             (set (q '[:find ?e ?n :where [?e :name ?n]] d))))
      (is (= (set raw-data)
             (set (export-data (db conn))))))
    (let [conn (connect "asami:mem://test13")
          {d :db-after} @(import-data conn (str raw-data))]
      (is (= #{[:a/node-10511 "Charles"]
               [:a/node-10513 "Jane"]}
             (set (q '[:find ?e ?n :where [?e :name ?n]] d))))
      (is (= (set raw-data)
             (set (export-data (db conn)))))))
  (testing "Loading entities durably"
    (let [[db-io db-new] #?(:clj ["asami:local://test-io" "asami:local://new-io"]
                            :cljs ["asami:mem://test-io" "asami:mem://new-io"])
          conn (connect db-io)
          {d :db-after} @(transact conn {:tx-data io-entities})
          r (set (q '[:find ?e ?n :where [?e :name ?n]] d))]
      #?(:clj
         (is (= #{[(node 1) "Charles"] [(node 3) "Jane"]} r))
         :cljs
         (do
           (is (= #{"Charles" "Jane"} (set (map second r))))
           (is (every? graph/node-type? (map first r)))))
      (let [serialized (pr-str (export-data (db conn)))
            new-conn (connect db-new)
            {d2 :db-after} @(import-data new-conn serialized)
            r2 (set (q '[:find ?e ?n :where [?e :name ?n]] d2))]
        #?(:clj
           (is (= #{[(node 1) "Charles"] [(node 3) "Jane"]} r2))
           :cljs
           (do
             (is (= #{"Charles" "Jane"} (set (map second r2))))
             (is (every? graph/node-type? (map first r2))))))
      (delete-database db-io)
      (delete-database db-new))))

(deftest test-database-delete
  (testing "Are deleted memory databases cleared"
    (let [db-name "asami:mem://test-del"
          conn (connect db-name)
          {d :db-after} @(transact conn {:tx-data io-entities})
          r (set (q '[:find ?e ?a ?v :where [?e ?a ?v]] d))]
      (is (= 13 (count r)))

      (delete-database db-name)

      (let [d2 (db conn)
            r2 (set (q '[:find ?e ?a ?v :where [?e ?a ?v]] d2))
            r3 (set (q '[:find ?e ?a ?v :where [?e ?a ?v]] conn))
            r-old (set (q '[:find ?e ?a ?v :where [?e ?a ?v]] d))]
        (is (empty? r2))
        (is (empty? r3))
        (is (= 13 (count r-old))))

      (is (nil? (get @a/connections db-name)))
      (let [conn2 (connect db-name)
            {dx2 :db-after} @(transact conn2 {:tx-triples [[:mem/node-1 :property "a"]
                                                           [:mem/node-2 :property "b"]
                                                           [:mem/node-3 :property "c"]]})
            rx2 (q '[:find ?e ?a ?v :where [?e ?a ?v]] dx2)]
        ;; updating the old connection after retrieving a new one will fail
        (is (thrown-with-msg? ExceptionInfo #"Updating a detached connection"
                              @(transact conn {:tx-triples [[:mem/node-4 :property "value"]
                                                            [:mem/node-5 :property "other"]]})))

        (delete-database db-name)
        (is (nil? (get @a/connections db-name)))
        ;; does transacting into a detached database work?
        (let [{dx2 :db-after} @(transact conn2 {:tx-triples [[:mem/node-6 :property "d"]]})
              rx2 (q '[:find ?e ?a ?v :where [?e ?a ?v]] dx2)]
          ;; is it now reattached?
          (is (identical? conn2 (get @a/connections db-name)))
          (is (= 1 (count rx2))))))))

(deftest test-update-unowned
  (testing "Doing an update on an attribute that references a top level entity"
    (let [c *conn*
          {d1 :db-after :as tx1} @(transact
                                   c {:tx-data
                                      [{:db/ident :p1
                                        :person/name "Person"}
                                       {:db/ident :c1
                                        :change/person {:db/ident :p1}
                                        :change/time "T1"}
                                       {:article/name "Article"
                                        :db/ident :a1
                                        :article/change {:db/ident :c1}}]})
          {d2 :db-after :as tx2} @(transact
                                   c {:tx-data
                                      [{:db/ident :a1
                                        :article/change' {:db/ident :c2}}
                                       {:db/ident :c2
                                        :change/person {:db/ident :p1}
                                        :change/time "T2"}]})]
      (is (= (entity d1 :a1 true)
             {:article/name "Article"
              :article/change {:change/person {:person/name "Person"}
                               :change/time "T1"}}))
      (is (= (entity d1 :c1 true)
             {:change/person {:person/name "Person"}
              :change/time "T1"}))
      (is (= (entity d1 :p1 true)
             {:person/name "Person"}))

      (is (= (entity d2 :a1 true)
             {:article/name "Article"
              :article/change {:change/person {:person/name "Person"}
                               :change/time "T2"}}))
      (is (= (entity d2 :c1 true)
             {:change/person {:person/name "Person"}
              :change/time "T1"}))
      (is (= (entity d2 :c2 true)
             {:change/person {:person/name "Person"}
              :change/time "T2"}))
      (is (= (entity d2 :p1 true)
             {:person/name "Person"})))))

#?(:cljs (run-tests))
