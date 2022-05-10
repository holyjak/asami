(ns ^{:doc "Entity to triple mapping for the transaction api.
            This handles conversion of entities as well as managing updates."
      :author "Paula Gearon"}
    asami.entities
    (:require [asami.storage :as storage :refer [DatabaseType]]
              [asami.graph :as gr]
              [asami.entities.general :refer [EntityMap GraphType]]
              [asami.entities.writer :as writer :refer [Triple]]
              [zuko.util :as util]
              [zuko.node :as node]
              #?(:clj  [schema.core :as s]
                 :cljs [schema.core :as s :include-macros true])))


(defn ^:private annotated-attribute?
  "Checks if an attribute has been annotated with a character"
  [c a]  ;; usually a keyword, but attributes can be other things
  (and (keyword a) (= c (last (name a)))))

(def ^:private update-attribute?
  "Checks if an attribute indicates that it should be updated"
  (partial annotated-attribute? \'))

(def ^:private append-attribute?
  "Checks if an attribute indicates that the data is an array that should be appended to"
  (partial annotated-attribute? \+))

(defn- normalize-attribute
  "Converts an updating attribute to its normalized form"
  [a]
  (if-not (keyword? a)
    a
    (let [n (name a)]
      (keyword (namespace a) (subs n 0 (dec (count n)))))))

(s/defn ^:private contains-updates?
  "Checks if any part of the object is to be updated"
  [obj :- {s/Any s/Any}]
  (let [obj-keys (keys obj)]
    (or (some update-attribute? obj-keys)
        (some append-attribute? obj-keys)
        (some #(and (map? %) (contains-updates? %)) (vals obj)))))

(s/defn ^:private minus :- (s/maybe s/Num)
  [limit :- (s/maybe s/Num)
   n :- s/Num]
  (when limit (- limit n)))

(s/defn ^:private entity-triples :- [(s/one [Triple] "New triples")
                                     (s/one [Triple] "Retractions")
                                     (s/one {s/Any s/Any} "New list of ID mappings")
                                     (s/one #{s/Any} "Running total set of top-level IDs")]
  "Creates the triples to be added and removed for a new entity.
   graph: the graph the entity is to be added to
   obj: The entity to generate triples for
   existing-ids: When IDs are provided by the user, then they get mapped to the internal ID that is actually used.
                 This map contains a mapping of user IDs to the ID allocated for the entity
   top-ids: The IDs of entities that are inserted at the top level. These are accumulated and this set
            avoids the need to query for them."
  [graph :- GraphType
   {id :db/id ident :db/ident ident2 :id :as obj} :- EntityMap
   existing-ids :- {s/Any s/Any}
   top-ids :- #{s/Any}
   limit :- (s/maybe s/Num)]
  (let [[new-obj removals additions]
        (if (contains-updates? obj)
          (do
            (when-not (or id ident ident2)
              (throw (ex-info "Nodes to be updated must be identified with :db/id, :db/ident, or :id" obj)))
            (let [node-ref (cond
                             id (and (seq (gr/resolve-triple graph id '?a '?v)) id)
                             ident (ffirst (gr/resolve-triple graph '?r :db/ident ident))
                             ident2 (ffirst (gr/resolve-triple graph '?r :id ident2)))
                  _ (when-not node-ref (throw (ex-info "Cannot update a non-existent node" (select-keys obj [:db/id :db/ident :id]))))
                  ;; find the annotated attributes
                  obj-keys (keys obj)
                  update-attributes (set (filter update-attribute? obj-keys))
                  append-attributes (filter append-attribute? obj-keys)
                  ;; map annotated attributes to the unannotated form
                  attribute-map (->> (concat update-attributes append-attributes)
                                     (map (fn [a] [a (normalize-attribute a)]))
                                     (into {}))
                  ;; update attributes get converted, append attributes get removed
                  clean-obj (->> obj
                                 (keep (fn [[k v :as e]] (if-let [nk (attribute-map k)] (when (update-attributes k) [nk v]) e)))
                                 (into {}))
                  ;; find existing attribute/values that match the updates
                  entity-av-pairs (gr/resolve-triple graph node-ref '?a '?v)
                  update-attrs (set (map attribute-map update-attributes))
                  ;; determine what needs to be removed
                  removal-pairs (filter (comp update-attrs first) entity-av-pairs)
                  removals (mapcat (partial writer/existing-triples graph node-ref) removal-pairs)

                  ;; find the lists that the appending attributes refer to
                  append-attrs (set (map attribute-map append-attributes))
                  ;; find what should be the heads of lists, removing any that aren't list heads
                  attr-heads (->> entity-av-pairs
                                  (filter (comp append-attrs first))
                                  (filter #(seq (gr/resolve-triple graph (second %) :a/first '?v))))
                  ;; find any appending attributes that are not in use. These are new arrays
                  remaining-attrs (reduce (fn [attrs [k v]] (disj attrs k)) append-attrs attr-heads)
                  ;; reassociate the object with any attributes that are for new arrays, making it a singleton array
                  append->annotate (into {} (map (fn [a] [(attribute-map a) a]) append-attributes))
                  new-obj (reduce (fn [o a] (assoc o a [(obj (append->annotate a))])) clean-obj remaining-attrs)
                  ;; find tails function
                  find-tail (fn [node]
                              (if-let [n (ffirst (gr/resolve-triple graph node :a/rest '?r))]
                                (recur n)
                                node))
                  ;; create appending triples
                  append-triples (mapcat (fn [[attr head]]
                                           (let [v (obj (append->annotate attr))
                                                 new-node (node/new-node graph)]
                                             [[(find-tail head) :a/rest new-node] [new-node :a/first v] [head :a/contains v]])) attr-heads)]
              (if (and limit (> (count append-triples) limit))
                (throw (ex-info "Limit reached" {:overflow true}))
                [new-obj removals append-triples])))
          [obj nil nil])

        [triples ids new-top-ids] (writer/ident-map->triples graph
                                                             new-obj
                                                             existing-ids
                                                             top-ids
                                                             (minus limit (count additions)))

        ;; if updates occurred new entity statements are redundant
        triples (if (or (seq removals) (seq additions) (not (identical? obj new-obj)))
                  (remove #(= :a/entity (second %)) triples)
                  triples)]
    [(concat triples additions) removals ids new-top-ids]))

(defn- vec-rest
  "Takes a vector and returns a vector of all but the first element. Same as (vec (rest s))"
  [s]
  #?(:clj (subvec (vec s) 1)
     :cljs (vec (rest s))))

(defn- temp-id?
  "Tests if an entity ID is a temporary ID"
  [i]
  (and (number? i) (neg? i)))

(defn resolve-lookup-refs [graph i]
  (when (writer/lookup-ref? i)
    (ffirst (gr/resolve-triple graph '?r (first i) (second i)))))

(defn- resolve-if-lookup-ref
  "If `i` is a lookup-ref such as `[:id \"myid\"]` then look it up in existing entities (via `graph`) or
  the entities currently being created (via the `id-map`). Otherwise return i as-is."
  [graph id-map i]
  (or (when (writer/lookup-ref? i)
        (or (resolve-lookup-refs graph i)
            ;; lookup-ref is e.g. `[:id "myid"]`; only the value is used in id mapping
            (get id-map (second i))))
      i))

(s/defn build-triples :- [(s/one [Triple] "Data to be asserted")
                          (s/one [Triple] "Data to be retracted")
                          (s/one {s/Any s/Any} "ID map of created objects")]
  "Converts a set of transaction data into triples.
  Returns a tuple containing [triples removal-triples tempids]"
  ([graph :- gr/GraphType
    data :- [s/Any]]
   (build-triples graph data nil))
  ([graph :- gr/GraphType
    data :- [s/Any]
    limit :- (s/maybe s/Num)]
   (let [[retract-stmts new-data] (util/divide' #(= :db/retract (first %)) data)
         retractions (mapv (comp (partial mapv (partial resolve-if-lookup-ref graph nil)) rest) retract-stmts)
         add-triples (fn [[acc racc ids top-ids :as last-result] obj]
                       (if (and limit (> (count acc) limit))
                         (reduced last-result)
                         (if (map? obj)
                           (try
                             (let [[triples rtriples new-ids new-top-ids] (entity-triples graph
                                                                                          obj
                                                                                          ids
                                                                                          top-ids
                                                                                          (minus limit (count acc)))]
                               [(into acc triples) (into racc rtriples) new-ids new-top-ids])
                             (catch #?(:clj Exception :cljs :default) e
                               (if-let [overflow (:overflow (ex-data e))]
                                 (reduced last-result)
                                 (throw e))))
                           (if (and (seqable? obj)          ; would it be ok performance-wise to turn this into if-let with destructuring to extract e a v to use below instead of (nth ...)?
                                    (= 4 (count obj))
                                    (= :db/add (first obj)))
                             (or
                               ;; Ex.: `[:db/add [:id X] :id X]` that creates a new entity
                               (when-let [ref (and (writer/lookup-ref? (nth obj 1))
                                                   (= (first (nth obj 1)) (nth obj 2))
                                                   (nth obj 1))]
                                 ;; NOTE: Use (binding [writer/*id-map* (volatile! ids)] (writer/get-ref id) ???
                                 (let [new-id (or #_(ids ref) (node/new-node graph))] ; I cannot see where `(ids ref)` would be non-nil in any well-formed tx
                                   [(conj acc (assoc (vec-rest obj) 0 new-id))
                                    racc
                                    (assoc ids (second ref) new-id)
                                    top-ids]))
                               ;; Ex.: [:db/add -1 :db/id -1]
                               (when (= (nth obj 2) :db/id)
                                (let [id (nth obj 3)]
                                  (when (temp-id? id)
                                    (let [new-id (or (ids id) (node/new-node graph))]
                                      [(conj acc (assoc (vec-rest obj) 2 new-id))
                                       racc
                                       (assoc ids (or id new-id) new-id)
                                       top-ids]))))
                              (let [triple (mapv #(or (ids %) (resolve-if-lookup-ref graph ids %)) (rest obj))]
                                [(conj acc triple) racc ids top-ids]))
                             (throw (ex-info (str "Bad data in transaction: " obj) {:data obj}))))))
         [triples rtriples id-map top-level-ids] (reduce add-triples [[] retractions {} #{}] new-data)
         triples (writer/backtrack-unlink-top-entities top-level-ids triples)]
     [triples rtriples id-map])))

