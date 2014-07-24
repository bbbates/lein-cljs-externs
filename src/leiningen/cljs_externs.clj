(ns leiningen.cljs-externs
  (:require [clojure.tools.reader :as ctr]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.data.json :as json]
            [leiningen.core.main :as lmain]))

(def ^:dynamic *current-file*)
(def ^:dynamic *warn-fn*)

(defn- collect-forms
  [contents]
  (try
    (binding [ctr/*read-eval* false
              ctr/*default-data-reader-fn* (fn [tag v] v)]
      (let [rdr (rt/string-push-back-reader contents)
            next-form-fn (partial ctr/read rdr false :done)]
        (loop [result (next-form-fn) forms []]
          (if (= result :done) forms
            (recur (next-form-fn) (conj forms result))))))
    (catch Throwable e
      (*warn-fn* "Could not read" (.getAbsolutePath *current-file*))
      [])))

(defn- collect-externs-from-form
  [form]
  (let [externs (atom [])]
    (clojure.walk/postwalk
     (fn [a] (swap! externs concat (:externs (meta a))) a)
     form)
    @externs))

(defn- extern-heirarchy
  [externs]
  (let [split-externs (map
                       (fn [ext]
                         (if (vector? ext)
                           (vec (cons (mapv symbol (clojure.string/split (name (first ext)) #"\.")) (rest ext)))
                           [(mapv symbol (clojure.string/split (name ext) #"\."))]))
                       externs)
        grouped-paths (group-by first split-externs)
        parent-children (zipmap (keys grouped-paths)
                                (map #(apply concat (map rest %)) (vals grouped-paths)))]
    (reduce
     (fn [heirachy [parent children]]
       (let [[path children] (if (seq children) [parent children] [(vec (butlast parent)) [(last parent)]])]
         (update-in heirachy path
                    (partial apply assoc)
                    (interleave children
                                (map #(get-in heirachy (conj path %) {}) children)))))
     {} parent-children)))

(defn- externs-as-string
  [externs]
  (let [extern-content
        (map (fn [[k v]]
               (format "var %s = %s;" k (json/write-str v)))
             externs)]
    (clojure.string/join "\n" extern-content)))


(defn gen-externs-from-slurpable
  "Takes a slurpable, reads as an EDN file, and from the various vars defined
  within, extracts the required externs specified within metadata.
  For example:

  ```
  (defn webgl-renderer
  ^{:externs [THREE.WebGLRenderer]}
  [params]
  (THREE.WebGLRenderer. (clj->js params)))
  ```
  In fact, you can define the extern metadata whereever you like, except on ns declarations forms, due
  to an outstanding clj bug that strips meta data from these forms.
  The scanner will walk every form defined in the slurpaple, looking for the :externs metadata."
  [slurpable]
  (when-let [contents (slurp slurpable)]
    (->> contents collect-forms (mapcat collect-externs-from-form))))

(defn- use-file?
  [f]
  (and
   (.isFile f)
   (= "cljs" (last (clojure.string/split (.getName f) #"\.")))))

(defn gen-externs-from-files
  [root-dirs]
  (let [files (mapcat file-seq root-dirs)
        cljs-files (filter use-file? files)
        externs-from-files (mapcat (fn [f] (binding [*current-file* f] (gen-externs-from-slurpable f))) cljs-files)
        extern-heirarchy (extern-heirarchy externs-from-files)]
    (externs-as-string extern-heirarchy)))

(def ^:private header
  "/* Generated by cljs-externs leiningen plugin */\n\n")

(defn cljs-externs
  "Generate cljs externs from externs tagged within .cljs files"
  [project & [build-id]]
  (if-let [cljs-build (if build-id
                        (or
                         (get-in project [:cljsbuild :builds (keyword build-id)])
                         (first (filter #(= build-id (:id %)) (get-in project [:cljsbuild :builds]))))
                        (first (get-in project [:cljsbuild :builds])))]
    (if-let [extern-gen-cfg (or (:extern-gen cljs-build)
                                (get-in project [:cljsbuild :extern-gen]))]
      (if-let [output-filename (:output-to extern-gen-cfg)]
        (do
          (binding [*warn-fn* lmain/info]
            (lmain/info "Generating externs from " (:source-paths cljs-build) " to " output-filename)
            (let [cljs-files (mapv clojure.java.io/file (:source-paths cljs-build))]
              (spit output-filename (str header (gen-externs-from-files cljs-files)))
              (lmain/info "Wrote externs to " output-filename))))
        (lmain/abort "No output file specified for generated externs."))
      (lmain/abort "Could not find extern-gen instructions in cljsbuild instructions."))
    (lmain/abort "Could not find cljsbuild instructions in project.")))

