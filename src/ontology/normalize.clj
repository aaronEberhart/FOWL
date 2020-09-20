(ns ontology.normalize
 "Functions that normalize ontology classes"
 (:require [ontology.axioms :as ax][ontology.expressions :as ex][ontology.components :as co]))

(def ^:no-doc one
  (constantly 1))
(def ^:no-doc zero
  (constantly 0))
(def ^:no-doc -or
  (constantly :or))
(def ^:no-doc -and
  (constantly :and))
(def ^:no-doc all
  (constantly :all))
(def ^:no-doc exists
  (constantly :exists))
(def ^:no-doc <=exists
  (constantly :<=exists))
(def ^:no-doc >=exists
  (constantly :>=exists))
(def ^:no-doc dataAll
  (constantly :dataAll))
(def ^:no-doc dataExists
  (constantly :dataExists))
(def ^:no-doc <=dataExists
  (constantly :<=dataExists))
(def ^:no-doc >=dataExists
  (constantly :>=dataExists))
(def ^:no-doc atomics
  #{:className :>=dataExists :<=dataExists :dataExists :dataAll :Self :nominal :partialRole :partialDataRole})

(defn negate
  "same as not, but doesn't make double negations"
  [c]
  (case (:innerType c)
    :not (:class c)
    :className
      (case (:name c)
      "Thing" co/Bot
      "Nothing" co/Top
      "topObjectProperty" co/TopRole
      "bottomObjectProperty" co/BotRole
      (ex/not c))
    (ex/not c)))

(defn- notFun [fun]
  (comp fun negate))

(defn- constantlyMapToClassSet [fun classes]
  (constantly (into #{} (map fun classes))))

(defn- checkInnerClass [class fun]
 (if (contains? atomics (:innerType (:class class)))
  class
  (update class :class fun)))

(defn- deMorgan [class fun]
 (case (:innerType (:class class))
  :className class
  :Self class
  :nominal class
  :partialRole class
  :partialDataRole class
  :and (update (update (:class class) :classes (constantlyMapToClassSet (notFun fun) (:classes (:class class)))) :innerType -or)
  :or (update (update (:class class) :classes (constantlyMapToClassSet (notFun fun) (:classes (:class class)))) :innerType -and)
  :exists (update (update (:class class) :class (notFun fun)) :innerType all)
  :all (update (update (:class class) :class (notFun fun)) :innerType exists)
  :<=exists (update (update (if (:class (:class class)) (checkInnerClass (:class class) fun) (:class class)) :nat inc) :innerType >=exists)
  :dataExists (update (update (:class class) :dataRange co/dataNot) :innerType dataAll)
  :dataAll (update (update (:class class) :dataRange co/dataNot) :innerType dataExists)
  :<=dataExists (update (update (:class class) :nat inc) :innerType >=dataExists)
  :=dataExists (if (> (:nat (:class class)) 0)
                (ex/or (update (update (:class class) :nat inc) :innerType >=dataExists)
                       (update (update (:class class) :nat dec) :innerType <=dataExists))
                (update (update (:class class) :nat one) :innerType >=dataExists))
  :>=dataExists (if (> (:nat (:class class)) 0)
                 (update (update (:class class) :nat dec) :innerType <=dataExists)
                 (ex/and (update (update (:class class) :nat zero) :innerType <=dataExists)
                         (update (:class class) :nat one)))
  :>=exists (let [class (if (:class (:class class)) (checkInnerClass (:class class) fun) (:class class))]
             (if (> (:nat class) 0)
              (update (update class :nat dec) :innerType <=exists)
              (ex/and (update (update class :nat zero) :innerType <=exists)
                      (update class :nat one))))
   :=exists (let [class (if (:class (:class class)) (checkInnerClass (:class class) fun) (:class class))]
             (if (> (:nat class) 0)
              (ex/or (update (update class :nat inc) :innerType >=exists)
                     (update (update class :nat dec) :innerType <=exists)))
              (update (update class :nat one) :innerType >=exists))
  (throw (Exception. (str  {:type ::notNormalizable :class class})))))

(defn- getClassNNF 
 "Gets the NNF for a class"
 [class]
  (case (:innerType class)
  :className class
  :Self class
  :nominal class
  :partialRole class
  :dataExists class
  :dataAll class
  :>=dataExists class
  :<=dataExists class
  :partialDataRole class
  :all (if (:class class) (checkInnerClass class getClassNNF) class)
  :exists (if (:class class) (checkInnerClass class getClassNNF) class)
  :>=exists (if (:class class) (checkInnerClass class getClassNNF) class)
  :<=exists (if (:class class) (checkInnerClass class getClassNNF) class)
  :or (update class :classes (constantlyMapToClassSet getClassNNF (:classes class)))
  :and (update class :classes (constantlyMapToClassSet getClassNNF (:classes class)))
  :=dataExists (ex/and (update class :innerType >=dataExists)(update class :innerType <=dataExists))
  :=exists (let [class (if (:class class) (update class :class getClassNNF) class)]
              (ex/and (update class :innerType >=exists) (update class :innerType <=exists)))
  :not (if (= :not (:innerType (:class class)))
        (getClassNNF (:class (:class class)))
        (deMorgan class getClassNNF))
  (throw (Exception. (str  {:type ::notNormalizable :class class})))))

(defn- classesPermutations [classes]
  (cons (cons (peek classes) (cons (first classes) nil)) (if (> (count classes) 2) (partition 2 1 classes))))

(defn- disjToImp
  ([classes]
    (reduce disjToImp #{} classes))
  ([classes pair]
    (conj classes (ax/classImplication (first pair) (negate (first (rest pair)))))))

(defn- equivToImp
  ([classes]
    (reduce equivToImp #{} classes))
  ([classes pair]
    (conj classes (ax/classImplication (first pair) (first (rest pair))) (ax/classImplication (first (rest pair)) (first pair)))))

(defn- disjOrToImp
  ([class classes](reduce disjToImp #{} classes))
  ([classes class1 class2](throw (Exception. (str  {:type ::notNormalizable :class classes})))))

(defn toClassImplications 
 "Converts an axiom to an equivalent axiom or set of axioms that are class implications"
 [axiom]
 (case (:innerType axiom)
  :classImplication axiom
  :disjClasses (disjToImp (classesPermutations (into [] (:classes axiom))))
  :=classes (equivToImp (classesPermutations (into [] (:classes axiom))))
  :disjOr (apply conj (toClassImplications (ax/disjClasses (:classes axiom))) (toClassImplications (ax/=classes [(:class axiom) (apply ex/or (:classes axiom))])))
  (throw (Exception. (str  {:type ::incompatibleClassAxiom :axiom axiom})))))

(defn- getClassAxiomNNF 
 "Gets the NNF of a class axiom"
 [axiom]
 (if (= (:innerType axiom) :classImplication)
  (update (update axiom :consequent getClassNNF) :antecedent getClassNNF)
  (map getClassAxiomNNF (toClassImplications axiom))))

(defn getNNF 
 "Gets the NNF of any axiom or class. (anything besides a class axiom returns itself)"
 [thing]
 (case (:type thing)
  :axiom (if (= (:outerType thing) :classAxiom)
          (getClassAxiomNNF thing)
          thing)
  :class (getClassNNF thing)
  thing))

(defn- getClassDSNF [class]
 (case (:innerType class)
  :className class
  :Self class
  :top class
  :bot class
  :nominal class
  :partialRole class
  :dataExists class
  :dataAll class
  :>=dataExists class
  :<=dataExists class
  :partialDataRole class
  :all (if (:class class) (checkInnerClass class getClassDSNF) class)
  :exists (if (:class class) (checkInnerClass class getClassDSNF) class)
  :>=exists (if (:class class) (checkInnerClass class getClassDSNF) class)
  :<=exists (if (:class class) (checkInnerClass class getClassDSNF) class)
  :or (update class :classes (constantlyMapToClassSet getClassDSNF (:classes class)))
  :and (negate (update (update class :classes (constantlyMapToClassSet (notFun getClassDSNF) (:classes class))) :innerType -or))
  :=dataExists (negate (ex/or (update (update class :nat (if (> (:nat class) 0) inc one)) :innerType >=dataExists)
                              (update (update class :nat (if (> (:nat class) 0) dec zero)) :innerType <=dataExists)))
  :=exists (let [class (if (:class class) (update class :class getClassDSNF) class)]
            (negate (ex/or (update (update class :nat (if (> (:nat class) 0) inc one)) :innerType >=exists) 
                           (update (update class :nat (if (> (:nat class) 0) dec zero)) :innerType <=exists))))
  :not (case (:innerType (:class class))
        :not (getClassDSNF (:class (:class class)))
        :or (update class :class (constantly (update (:class class) :classes (constantlyMapToClassSet getClassDSNF (:classes (:class class))))))
        :=exists (let [class (negate (update class :class getClassDSNF)) _ (prn "D" class)] class)
        :=dataExists (update class :class getClassDSNF)
        (deMorgan class getClassDSNF))
  (throw (Exception. (str  {:type ::notNormalizable :class class})))))

(defn- getClassAxiomDSNF [axiom]
  (if (= (:innerType axiom) :classImplication)
    (ex/or (getClassDSNF (negate (:antecedent axiom))) (getClassDSNF (:consequent axiom)))
    (map getClassAxiomDSNF (toClassImplications axiom))))

(defn ^:no-doc getDSNF 
 "Gets the Disjunctive Syntactic Normal Form for an axiom or class. Unfinished"
 [thing]
 (case (:type thing)
  :axiom (if (= (:outerType thing) :classAxiom)
          (getClassAxiomDSNF thing)
          thing)
  :class (getClassDSNF thing)
  thing))

(defn- getClassCSNF [class]
  (case (:innerType class)
  :className class
  :Self class
  :nominal class
  :partialRole class
  :dataExists class
  :dataAll class
  :>=dataExists class
  :<=dataExists class
  :partialDataRole class
  :all (if (:class class) (checkInnerClass class getClassCSNF) class)
  :exists (if (:class class) (checkInnerClass class getClassCSNF) class)
  :>=exists (if (:class class) (checkInnerClass class getClassCSNF) class)
  :<=exists (if (:class class) (checkInnerClass class getClassCSNF) class)
  :or (negate (update (update class :classes (constantlyMapToClassSet (notFun getClassCSNF) (:classes class))) :innerType -and))
  :and (update class :classes (constantlyMapToClassSet getClassCSNF (:classes class)))
  :=dataExists (ex/and (update class :innerType >=dataExists)(update class :innerType <=dataExists))
  :=exists (let [class (if (:class class) (update class :class getClassCSNF) class)]
            (ex/and (update class :innerType >=exists) (update class :innerType <=exists)))
  :not (case (:innerType (:class class))
        :not (getClassCSNF (:class (:class class)))
        :and (update class :class (constantly (update (:class class) :classes (constantlyMapToClassSet getClassCSNF (:classes (:class class))))))
        :=exists (let [class (update class :class getClassCSNF) _ (prn "C" class)] class)
        :=dataExists (update class :class getClassCSNF)
        (deMorgan class getClassCSNF))
  (throw (Exception. (str  {:type ::notNormalizable :class class})))))

(defn- getClassAxiomCSNF [axiom]
  (if (= (:innerType axiom) :classImplication)
   (negate (ex/and (getClassCSNF (:antecedent axiom)) (getClassCSNF (negate (:consequent axiom)))))
   (map getClassAxiomCSNF (toClassImplications axiom))))

(defn ^:no-doc getCSNF 
 "Gets the Conjunctive Syntactic Normal Form for an axiom or class. Unfinished"
 [thing]
 (case (:type thing)
  :axiom (if (= (:outerType thing) :classAxiom)
          (getClassAxiomCSNF thing)
          thing)
  :class (getClassCSNF thing)
  thing))
