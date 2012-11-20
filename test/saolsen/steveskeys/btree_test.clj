(ns saolsen.steveskeys.btree-test
  (:require [taoensso.nippy :as nippy])
  (:use clojure.test
        saolsen.steveskeys.btree))

;; Manually built tree for testing the search
(def test-nodes
  {
   :root (->BPlusTreeNode [{:key (nippy/freeze-to-bytes 2) :val :a}
                           {:key (nippy/freeze-to-bytes 5) :val :b}
                           {:key (nippy/freeze-to-bytes 9) :val :c}
                           {:key nil :val :d}])
   :a (->BPlusTreeLeaf [{:key (nippy/freeze-to-bytes 1) :val :one}
                        {:key (nippy/freeze-to-bytes 2) :val :two}])
   :b (->BPlusTreeLeaf [{:key (nippy/freeze-to-bytes 3) :val :three}
                        {:key (nippy/freeze-to-bytes 4) :val :four}
                        {:key (nippy/freeze-to-bytes 5) :val :five}])
   :c (->BPlusTreeLeaf [{:key (nippy/freeze-to-bytes 6) :val :six}
                        {:key (nippy/freeze-to-bytes 7) :val :seven}
                        {:key (nippy/freeze-to-bytes 8) :val :eight}
                        {:key (nippy/freeze-to-bytes 9) :val :nine}])
   :d (->BPlusTreeLeaf [{:key (nippy/freeze-to-bytes 10) :val :ten}])
   :one "one"
   :two "two"
   :three "three"
   :four "four"
   :five "five"
   :six "six"
   :seven "seven"
   :eight "eight"
   :nine "nine"
   :ten "ten"
   }
  )

(def ks [1 2 3 4 5 6 7 8 9 10])
(def vs ["one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten"])

(def ks2 (range 100))
(def chars (map char (range 97 (+ 97 26))))
(def vs2 (take 100
               (flatten
                (map
                 (fn [a]
                   (map (fn [b] (str a b)) chars))
                 chars))))

(defn test-they-all-exist
  "checks that all ks are mapped to vs in tree, also checks that if the key
   isn't in the tree it returns nil"
  [tree ks vs]
  (doseq [[k v] (map #(vector %1 %2) ks vs)]
    (let [result (get tree (nippy/freeze-to-bytes k))]
      (is (= result v))))
  (is (= (get tree (nippy/freeze-to-bytes 11)) nil)))

(deftest test-btree-search
   (testing "Search for a key in a premade tree")
   (let [tree
         (->PersistantBPlusTree (:root test-nodes) #(get test-nodes %) nil 1)]
    (test-they-all-exist tree ks vs)))

;; Tree that stores the nodes in a clojure map, used to test construction.
(deftest test-btree-buildin
  (testing "Build and search a tree, inserting in a bunch of random orders")
  (dotimes [n 10]
    (let [nextid (ref 0)
          nodes (ref {:root (->BPlusTreeLeaf [])})
          tree (->PersistantBPlusTree (:root @nodes)
                                      #(get @nodes %)
                                      #(dosync
                                        (let [id (alter nextid inc)]
                                          (alter nodes assoc id %)
                                          id))
                                      4)
          s (flatten
             (shuffle (map #(vector (nippy/freeze-to-bytes %1) %2) ks2 vs2)))
          _ (println s)
          added
          (apply (partial assoc tree) s)
          _ (test-they-all-exist added ks2 vs2)
          ;; test that after tree is made, duplicates can be replaced correctly
          t (apply (partial assoc added) (map #(vector)))]

)))
