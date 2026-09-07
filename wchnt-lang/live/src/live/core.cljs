(ns live.core
  "Live page: wiki storage, CodeMirror, canvas harness, Run/Stop."
  (:require-macros [live.embed :refer [blank-source example-source]])
  (:require [clojure.string :as str]
            [live.editor :as editor]
            [live.runtime :as runtime]
            [live.storage :as storage]
            [goog.object :as gobj]))

(defonce !editor (atom nil))
(defonce !harness (atom nil))
(defonce !current-page (atom nil))
(defonce !save-timer (atom nil))
(defonce !history-silent (atom false))

(defn- page-url
  [page-name]
  (str "?page=" (js/encodeURIComponent page-name)))

(defn- page-from-location
  []
  (try
    (let [params (js/URLSearchParams. (.-search js/location))
          raw (.get params "page")]
      (when (seq raw)
        (js/decodeURIComponent raw)))
    (catch :default _ nil)))

(defn- push-page-history!
  [page-name]
  (.pushState js/history #js {:page page-name} "" (page-url page-name)))

(defn- replace-page-history!
  [page-name]
  (.replaceState js/history #js {:page page-name} "" (page-url page-name)))

(defn- history-page-name
  [evt]
  (or (gobj/get (.-state evt) "page")
      (page-from-location)))

(defn- maybe-push-history!
  [from-page to-page]
  (when (and (not @!history-silent)
             (not= from-page to-page))
    (push-page-history! to-page)))

(defn- el [id]
  (.getElementById js/document id))

(defn- show-error!
  [msg]
  (let [node (el "error")]
    (gobj/set node "hidden" false)
    (gobj/set node "textContent" msg)))

(defn- show-info!
  [msg]
  (let [node (el "error")]
    (gobj/set node "hidden" false)
    (gobj/set node "textContent" msg)
    (gobj/set node "className" "info")))

(defn- clear-message!
  []
  (let [node (el "error")]
    (gobj/set node "hidden" true)
    (gobj/set node "textContent" "")
    (gobj/set node "className" "")))

(defn- err-message
  [e]
  (or (ex-message e)
      (.-message e)
      (str e)))

(defn- set-running!
  [running?]
  (gobj/set (el "run") "disabled" running?)
  (gobj/set (el "stop") "disabled" (not running?)))

(defn- blur-editor!
  []
  (when-let [ed @!editor]
    (when-let [input (.getInputField ed)]
      (.blur input))))

(defn- focus-stage-keys!
  []
  (when-let [keys-el (el "stage-keys")]
    (.focus keys-el)))

(defn- show-run-modal!
  []
  (blur-editor!)
  (gobj/set (.-body js/document) "style" "overflow: hidden")
  (gobj/set (el "run-modal") "hidden" false)
  (js/setTimeout focus-stage-keys! 0))

(defn- hide-run-modal!
  []
  (gobj/set (el "run-modal") "hidden" true)
  (gobj/set (.-body js/document) "style" ""))

(defn- update-title!
  [page-name]
  (gobj/set (el "page-title") "textContent" (or page-name "—")))

(defn- cancel-scheduled-save!
  []
  (when-let [t @!save-timer]
    (js/clearTimeout t))
  (reset! !save-timer nil))

(defn- save-current!
  []
  (when-let [page @!current-page]
    (when-let [ed @!editor]
      (try
        (storage/save-page! page (editor/text ed))
        (catch :default e
          (show-error! (str "Save failed: " (err-message e))))))))

(defn- flush-save!
  "Cancel any pending autosave and persist the page being edited now."
  []
  (cancel-scheduled-save!)
  (save-current!))

(defonce !loading-page (atom false))

(defn- schedule-save!
  []
  (when-not @!loading-page
    (cancel-scheduled-save!)
    (reset! !save-timer
            (js/setTimeout save-current! 400))))

(defn- load-editor!
  [content]
  (when-let [ed @!editor]
    (reset! !loading-page true)
    (editor/set-text! ed content)
    (reset! !loading-page false)))

(defn- open-page-with-content!
  "Switch pages after saving the current one; store explicit content."
  [page-name content]
  (let [from-page @!current-page]
    (flush-save!)
    (stop!)
    (storage/save-page! page-name content)
    (reset! !current-page page-name)
    (update-title! page-name)
    (load-editor! content)
    (maybe-push-history! from-page page-name)))

(defn- open-page!
  "Switch pages after saving the current one; load destination from storage."
  [page-name]
  (let [from-page @!current-page
        trimmed (str/trim page-name)
        existing (storage/get-page trimmed)
        content (or existing (storage/blank-page trimmed))]
    (flush-save!)
    (stop!)
    (when-not existing
      (storage/save-page! trimmed content))
    (storage/set-current-page! trimmed)
    (reset! !current-page trimmed)
    (update-title! trimmed)
    (load-editor! content)
    (maybe-push-history! from-page trimmed)))

(defn- navigate-to-page!
  [page-name]
  (open-page! page-name))

(defn- stop!
  []
  (when-let [h @!harness]
    (.stop h))
  (hide-run-modal!)
  (set-running! false))

(defn- start-run!
  []
  (stop!)
  (clear-message!)
  (flush-save!)
  (try
    (let [source (editor/text @!editor)
          harness @!harness
          result (runtime/prepare source
                                  (.-wchntGraphics harness)
                                  (.-input harness))]
      (case (:kind result)
        :documentation (show-info! (:message result))
        :library (show-info! (:message result))
        :program (do (show-run-modal!)
                     (.start harness (:init result) (:step result)
                             (fn [e]
                               (stop!)
                               (show-error! (err-message e))))
                     (set-running! true))))
    (catch :default e
      (stop!)
      (show-error! (err-message e)))))

(defn- page-name-input
  []
  (el "page-name-input"))

(defn- prompt-page-name!
  []
  (let [input (page-name-input)
        raw (when input (.-value input))]
    (when (seq (str/trim raw))
      (str/trim raw))))

(defn- on-save!
  []
  (try
    (if @!current-page
      (do (flush-save!)
          (show-info! (str "Saved " @!current-page ".")))
      (show-error! "No page open"))
    (catch :default e
      (show-error! (err-message e)))))

(defn- on-page-name-submit!
  "Open an existing page or create a blank one when the name is new."
  []
  (try
    (if-let [name (prompt-page-name!)]
      (do (navigate-to-page! name)
          (clear-message!)
          (set! (.-value (page-name-input)) ""))
      (show-error! "Enter a page name"))
    (catch :default e
      (show-error! (err-message e)))))

(defn- on-load-example!
  []
  (let [example (js/prompt "Example (bounce_canvas, square_canvas, pollution_canvas):" "pollution_canvas")]
    (when example
      (let [name (str/trim example)
            content (case name
                      "bounce_canvas" (example-source "bounce_canvas")
                      "square_canvas" (example-source "square_canvas")
                      "pollution_canvas" (example-source "pollution_canvas")
                      nil)]
        (if content
          (open-page-with-content! name content)
          (show-error! (str "Unknown example '" name "'")))))))

(defn- open-pages-index!
  []
  (clear-message!)
  (open-page-with-content! storage/index-page-name (storage/pages-index-content)))

(defn- on-popstate!
  [evt]
  (when-let [page (history-page-name evt)]
    (reset! !history-silent true)
    (try
      (clear-message!)
      (open-page! page)
      (catch :default e
        (show-error! (err-message e)))
      (finally
        (reset! !history-silent false)))))

(defn- on-export-wiki!
  []
  (try
    (flush-save!)
    (let [text (storage/export-all-pages)]
      (if (seq text)
        (let [blob (js/Blob. #js [text] #js {:type "text/plain;charset=utf-8"})
              url (.createObjectURL js/URL blob)
              a (.createElement js/document "a")]
          (set! (.-href a) url)
          (set! (.-download a) "wchnt-wiki.wcn")
          (.appendChild (.-body js/document) a)
          (.click a)
          (.removeChild (.-body js/document) a)
          (.revokeObjectURL js/URL url)
          (show-info! (str "Exported " (count (storage/list-pages)) " pages.")))
        (show-error! "No pages to export.")))
    (catch :default e
      (show-error! (err-message e)))))

(defn- on-import-wiki!
  []
  (when-let [input (el "wiki-import-file")]
    (set! (.-value input) "")
    (.click input)))

(defn- on-import-file-selected!
  [evt]
  (when-let [file (aget (.. evt -target -files) 0)]
    (let [reader (js/FileReader.)]
      (set! (.-onload reader)
            (fn [e]
              (try
                (flush-save!)
                (let [text (.. e -target -result)
                      imported (storage/import-all-pages! text)
                      first-page (first imported)]
                  (open-page! first-page)
                  (clear-message!)
                  (show-info! (str "Loaded " (count imported) " pages.")))
                (catch :default err
                  (show-error! (err-message err))))))
      (.readAsText reader file "UTF-8"))))

(defn- bind-ui!
  []
  (.addEventListener js/window "popstate" on-popstate!)
  (.addEventListener (el "run") "click" start-run!)
  (.addEventListener (el "stop") "click" stop!)
  (.addEventListener (el "close-run") "click" stop!)
  (.addEventListener (el "all-pages") "click" open-pages-index!)
  (.addEventListener (el "save-page") "click" on-save!)
  (.addEventListener (el "export-wiki") "click" on-export-wiki!)
  (.addEventListener (el "import-wiki") "click" on-import-wiki!)
  (.addEventListener (el "wiki-import-file") "change" on-import-file-selected!)
  (.addEventListener (el "new-page") "click" on-page-name-submit!)
  (.addEventListener (el "load-example") "click" on-load-example!)
  (.addEventListener (page-name-input) "keydown"
                     (fn [evt]
                       (when (= "Enter" (.-key evt))
                         (.preventDefault evt)
                         (on-page-name-submit!)))))

(defn- persist-on-exit!
  []
  (flush-save!))

(defn- init-wiki!
  []
  (when-not (storage/storage-available?)
    (show-error!
     (str "Wiki storage unavailable — pages will not persist after reload. "
          "Serve live/public over http:// (e.g. python3 -m http.server) "
          "instead of opening index.html as file://.")))
  (let [editor-el (el "editor")
        canvas (el "stage")
        keys-el (el "stage-keys")
        harness (.create js/WCHNTHarness canvas keys-el)
        start-content (blank-source)
        start-page (or (page-from-location)
                       (storage/current-page)
                       "welcome")]
    (when-not (storage/get-page start-page)
      (storage/save-page! start-page
                          (if (= start-page "welcome")
                            start-content
                            (storage/blank-page start-page))))
    (reset! !harness harness)
    (reset! !editor (editor/mount! editor-el
                                    (storage/get-page start-page)
                                    navigate-to-page!))
    (reset! !history-silent true)
    (reset! !current-page start-page)
    (storage/set-current-page! start-page)
    (update-title! start-page)
    (load-editor! (storage/get-page start-page))
    (replace-page-history! start-page)
    (reset! !history-silent false)
    (.on @!editor "change" (fn [_ _] (schedule-save!)))
    (.addEventListener js/window "beforeunload" (fn [_] (persist-on-exit!)))
    (.addEventListener js/window "pagehide" (fn [_] (persist-on-exit!)))
    (set-running! false)
    (bind-ui!)))

(defn ^:export init
  []
  (try
    (init-wiki!)
    (catch js/Error e
      (show-error! (str "Live init failed: " (err-message e))))
    (catch :default e
      (show-error! (str "Live init failed: " (err-message e))))))

(init)
