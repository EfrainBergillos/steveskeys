(ns saolsen.steveskeys.btree
  (:use [clojure.math.numeric-tower])
  (:import java.util.Arrays
           com.google.common.primitives.UnsignedBytes))

;; Helpers for working with byte arrays.
(defn bequals
  "Equality operator for byte arrays"
  [a b]
  (java.util.Arrays/equals a b))

;; If somebody has a pure java implementation of this I'd love to not include
;; guava.
(def byte-array-comparator
  (com.google.common.primitives.UnsignedBytes/lexicographicalComparator))

(defn bcompare
  "Comparison operator for byte arrays"
  [a b]
  (.compare byte-array-comparator a b))

;; Persistant B+ tree implementation.
;; An immutable and persistant B+ tree implementation that can be used anywhere
;; immutable structures are required (eg, atoms)
;;
;; Assumptions:
;; - keys and values are byte arrays.
;; - get-node is a referentially transparent function.
;; - (= node (get-node (add-node node)))

(defprotocol PReplace
  "protocol for nodes to change correctly"
  (add-kvps [node new-kvps] "adds or replaces one or more key value pairs")
  (split [node] "splits the node in two")
  (greatest-key [node] "greatest key")
  (serialize [node] "creates a map that can be serialized with nippy"))

;; Each node has a list of key value pairs.
;; {:kvps [{:key key1 :val node-id1} {:key key2 :val node-id2}] }
(defrecord BPlusTreeNode [kvps]
  PReplace
  (add-kvps [_ new-kvps]
    (if (= (count kvps) 0)
      (BPlusTreeNode. [(first new-kvps) (assoc (second new-kvps) :key nil)])
      (let [new-keys (map :key new-kvps)
            nil-replace? (some nil? new-keys)
            old (filter #(not (or (and nil-replace? (nil? (:key %)))
                                  (bequals (:key %) (first new-keys))
                                  (if (second new-keys)
                                    (bequals (:key %) (second new-keys))
                                    false)))
                        kvps)
            new (if nil-replace?
                  (let [n (first (filter #(nil? (:key %)) new-kvps))
                        o (first (filter #(not (nil? (:key %))) new-kvps))
                        to-sort (if o (conj old o) old)
                        sorted (vec (sort-by #(:key %) bcompare to-sort))]
                    (conj sorted n))
                  (let [sorted (vec (sort-by #(:key %) bcompare
                                             (into (butlast old) new-kvps)))]
                    (conj sorted (last kvps))))]
        (BPlusTreeNode. new))))

  (split [_]
    (let [s (count kvps)
          half (ceil (/ s 2))
          first-kvps (take half kvps)
          second-kvps (drop half kvps)
          split-key (:key (last first-kvps))]
      {:nodes
       [(BPlusTreeNode.
         (conj (vec (butlast first-kvps))
               {:key nil :val (:val (last first-kvps))}))
        (BPlusTreeNode. second-kvps)]
       :split-key split-key}))

  (greatest-key [_] (or (:key (last kvps))
                        (:key (last (butlast kvps)))))

  (serialize [_] {:kvps kvps :type :node}))

;; {:kvps [[key1 val1] [key2 val2]] }
(defrecord BPlusTreeLeaf [kvps]
  PReplace
  (add-kvps [_ new-kvps]
    (let [{:keys [key val] :as kvp} (first new-kvps)
          old (filter #(not (bequals (:key %) key)) kvps)
          new (sort-by #(:key %) bcompare (conj old kvp))]
      (BPlusTreeLeaf. new)))

  (split [_]
    (let [half (ceil (/ (count kvps) 2))
          first-kvps (take half kvps)
          second-kvps (drop half kvps)]
      {:nodes
       [(BPlusTreeLeaf. first-kvps)
        (BPlusTreeLeaf. second-kvps)]
       :split-key (:key (last first-kvps))}))

  (greatest-key [_] (:key (last kvps)))

  (serialize [_] {:kvps kvps :type :leaf}))

(defn deserialize
  "When things are serialized with nippy we use the to-freezable method
   on the leaf or node. This method is the reverse of that and turns what
   is thaw'd back into a node or leaf."
  [node]
  (let [type (:type node)]
    (cond
     (= type :node) (BPlusTreeNode. (:kvps node))
     (= type :leaf) (BPlusTreeLeaf. (:kvps node))
     :else (throw (Error. "Not a tree node type")))))

(defn- search-step-reducer
  "reduce function over the key value pairs"
  [search-key result kvp]
  (or result
      (let [{:keys [key val]} kvp
            c (bcompare search-key key)]
        ;; if the key is in the correct range for this node return the value
        (if (<= c 0)
          kvp
          nil))))

(defn- search-step
  "returns the next node id"
  [key node]
  (let [kvps (:kvps node)
        next (reduce (partial search-step-reducer key) nil (butlast kvps))]
    (or next
        (last kvps))))

(defn- path-to-leaf
  "returns the path to the leaf node that the key would go (or be) in, as well
   as the leaf node"
  [key root get-node-fn]
    (loop [node root
           path []]
      (if (instance? BPlusTreeLeaf node)
        {:path path :node node}
        (let [next (search-step key node)
              next-node (get-node-fn (:val next))
              next-path (conj path {:node node :key (:key next)})]
          (recur next-node next-path)))))

(defn node-reducer
  [start end {:keys [result lastkey]} {:keys [key val] :as kvp}]
  (if (and (>= (bcompare key start) 0)
           (or (nil? lastkey) (< (bcompare lastkey end) 0)))
    {:result (conj result kvp) :lastkey key}
    {:result result :lastkey key}))

(defn get-nodes
  [get-node node start end]
  (if (instance? BPlusTreeLeaf node)
    node
    (let [gk (greatest-key node)
          reducer (partial node-reducer start end)
          nodes (reduce
                 reducer
                 {:result [] :lastkey nil}
                 (butlast (:kvps node)))]
      (map #(get-node (:val %))
           (if (> (bcompare end gk) 0)
             (conj (:result nodes) (last (:kvps node)))
             (:result nodes))))))

(defn expand-to-leaves
  [get-node root start end]
  (loop [nodes [root]]
    (if (some #(instance? BPlusTreeNode %) nodes)
      (let [step (map #(get-nodes get-node % start end) nodes)]
        (recur (flatten step)))
      nodes)))

(defprotocol PTraversable
  "support for pulling a range of key/values in order of the keys"
  (traverse [this start end]
    "returns a lazy sequence of key/value pairs from start to end"))

(defprotocol PGetRootLoc
  "returns the root location"
  (get-root-loc [this] "returns the location of the root node"))

(deftype PersistantBPlusTree
    [root root-ptr get-node add-node bf]
  clojure.lang.Associative
  ;; assoc
  (assoc [_ key value]
    (let [new-record-id value
          {:keys [path node]} (path-to-leaf key root get-node)
          ordered (reverse path)]
      (loop [kvps [{:key key :val new-record-id}]
             n node
             k (:key (first ordered))
             remaining ordered]
        (let [new-node (add-kvps n kvps)
              split? (> (count (:kvps new-node)) bf)
              split-nodes (if split? (split new-node) nil)
              node-list (sort-by greatest-key bcompare
                                 (if split?
                                   (:nodes split-nodes)
                                   [new-node]))
              ids (map add-node node-list)
              new-kvps (if split?
                         [{:key (:split-key split-nodes)
                           :val (first ids)}
                          {:key k
                            :val (second ids)}]
                         [{:key k
                           :val (first ids)}])]
          (if (> (count remaining) 0)
            (recur new-kvps
                   (:node (first remaining))
                   (:key (second remaining))
                   (next remaining))
            (if (= (count new-kvps) 1)
                (PersistantBPlusTree.
                 (get-node (:val (first new-kvps)))
                 (:val (first new-kvps))
                 get-node
                 add-node
                 bf)
                (let [new-root (BPlusTreeNode. new-kvps)
                      id (add-node new-root)]
                  (PersistantBPlusTree.
                   (get-node id)
                   id
                   get-node
                   add-node
                   bf))))))))

  ;; get
  (valAt [_ key]
    ;; Recursively search down the tree for the key, returns it's value.
    (let [search (path-to-leaf key root get-node)
          node (:node search)]
       (:val (first (filter #(bequals key (:key %)) (:kvps node))))))

  PTraversable
  ;; traverse
  (traverse [_ start end]
    (let [leaves (expand-to-leaves get-node root start end)
          result (atom [])]
      (doseq [l leaves]
        (doseq [{:keys [key val]} (:kvps l)]
          (when (and (>= (bcompare key start) 0)
                     (<= (bcompare key end) 0))
            (swap! result conj {:key key :val val}))))
      @result))

  PGetRootLoc
  (get-root-loc [_] root-ptr)
)
