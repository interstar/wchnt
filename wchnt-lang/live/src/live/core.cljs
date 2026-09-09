(ns live.core
  "Live page: wiki storage, CodeMirror, canvas harness, Run."
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
  (gobj/set (el "run") "disabled" running?))

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
  (if-let [resolved (storage/resolve-page-name page-name)]
    (open-page! resolved)
    (show-error! (str "No page named '" page-name "'."))))

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

(defn- clear-page-name-input!
  []
  (set! (.-value (page-name-input)) ""))

(defn- on-go-page!
  "Open an existing page by name."
  []
  (try
    (if-let [name (prompt-page-name!)]
      (if (storage/resolve-page-name name)
        (do (navigate-to-page! name)
            (clear-message!)
            (clear-page-name-input!))
        (show-error! (str "No page named '" name "'.")))
      (show-error! "Enter a page name"))
    (catch :default e
      (show-error! (err-message e)))))

(defn- on-new-page!
  "Create a blank page with the given name."
  []
  (try
    (if-let [name (prompt-page-name!)]
      (if (storage/get-page name)
        (show-error! (str "Page '" name "' already exists. Use Go to open it."))
        (do (open-page-with-content! name (storage/blank-page name))
            (clear-message!)
            (clear-page-name-input!)))
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

(defn- reset-wiki!
  "Clear all saved wiki pages and reload. Destructive; callers confirm first."
  []
  (storage/reset-pages!)
  (js/location.reload))

(defn- on-reset-shortcut!
  [evt]
  ;; Ctrl+Shift+Alt+W — invisible in the UI, deliberate, and confirmed.
  (when (and (.-ctrlKey evt)
             (.-shiftKey evt)
             (.-altKey evt)
             (not (.-metaKey evt))
             (= "w" (str/lower-case (or (.-key evt) ""))))
    (.preventDefault evt)
    (when (js/confirm "Reset the WCHNT wiki? All saved pages will be deleted and the default examples reloaded.")
      (reset-wiki!))))

(def theme-key "wchnt.theme")

(defn- current-theme
  []
  (let [saved (.getItem js/localStorage theme-key)]
    (if (or (= saved "light") (= saved "dark")) saved "dark")))

(defn- cm-theme
  [theme]
  (if (= theme "light") "default" "material-darker"))

(defn- apply-theme!
  [theme]
  (.setAttribute (.-documentElement js/document) "data-theme" theme)
  (when-let [cm @!editor]
    (editor/set-theme! cm (cm-theme theme)))
  (when-let [btn (el "theme-toggle")]
    (gobj/set btn "textContent" (if (= theme "light") "Dark" "Light"))))

(defn- on-theme-toggle!
  []
  (let [next (if (= (current-theme) "light") "dark" "light")]
    (.setItem js/localStorage theme-key next)
    (apply-theme! next)))

(defn- bind-ui!
  []
  (.addEventListener js/window "popstate" on-popstate!)
  (.addEventListener js/window "keydown" on-reset-shortcut!)
  (.addEventListener (el "run") "click" start-run!)
  (.addEventListener (el "close-run") "click" stop!)
  (.addEventListener (el "all-pages") "click" open-pages-index!)
  (.addEventListener (el "save-page") "click" on-save!)
  (.addEventListener (el "export-wiki") "click" on-export-wiki!)
  (.addEventListener (el "import-wiki") "click" on-import-wiki!)
  (.addEventListener (el "wiki-import-file") "change" on-import-file-selected!)
  (.addEventListener (el "go-page") "click" on-go-page!)
  (.addEventListener (el "new-page") "click" on-new-page!)
  (.addEventListener (el "load-example") "click" on-load-example!)
  (.addEventListener (el "theme-toggle") "click" on-theme-toggle!)
  (.addEventListener (page-name-input) "keydown"
                     (fn [evt]
                       (when (= "Enter" (.-key evt))
                         (.preventDefault evt)
                         (on-go-page!)))))

(defn- persist-on-exit!
  []
  (flush-save!))

(def seed-page-names
  "Default wiki pages seeded from live/public/seed/ on first visit."
  ["welcome" "bounce" "shapes" "pollution"])

(defn- seed-page!
  "Fetch a default page from live/public/seed/ and save it — but only if the
  user doesn't already have a page of that name, so user data is never
  overwritten. Falls back to a compiled-in welcome when the fetch fails."
  [name]
  (when-not (storage/get-page name)
    (-> (js/fetch (str "seed/" name ".wcn"))
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.text resp)
                   (throw (js/Error. (str "seed fetch failed: " (.-status resp)))))))
        (.then (fn [text]
                 (when-not (storage/get-page name)
                   (storage/save-page! name text)
                   (when (= name @!current-page)
                     (load-editor! text)))))
        (.catch (fn [_]
                  (when (and (= name "welcome") (not (storage/get-page name)))
                    (storage/save-page! name (blank-source))
                    (when (= name @!current-page)
                      (load-editor! (blank-source)))))))))

(defn- seed-default-pages!
  []
  (doseq [name seed-page-names]
    (seed-page! name)))

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
        theme (current-theme)
        start-page (or (page-from-location)
                       (storage/current-page)
                       "welcome")
        start-content (or (storage/get-page start-page)
                          (if (= start-page "welcome")
                            (blank-source)
                            (storage/blank-page start-page)))]
    (apply-theme! theme)
    ;; Seed pages are written asynchronously below; don't pre-save them here or
    ;; the seed step would skip them. Non-seed pages keep the old behaviour.
    (when-not (or (storage/get-page start-page)
                  (some #{start-page} seed-page-names))
      (storage/save-page! start-page (storage/blank-page start-page)))
    (reset! !harness harness)
    (reset! !editor (editor/mount! editor-el
                                    start-content
                                    navigate-to-page!
                                    (cm-theme theme)))
    (reset! !history-silent true)
    (reset! !current-page start-page)
    (storage/set-current-page! start-page)
    (update-title! start-page)
    (load-editor! start-content)
    (replace-page-history! start-page)
    (reset! !history-silent false)
    (.on @!editor "change" (fn [_ _] (schedule-save!)))
    (.addEventListener js/window "beforeunload" (fn [_] (persist-on-exit!)))
    (.addEventListener js/window "pagehide" (fn [_] (persist-on-exit!)))
    (set-running! false)
    (bind-ui!)
    ;; Hidden developer helper: window.wchntReset() clears pages and reloads.
    (gobj/set js/window "wchntReset" reset-wiki!)
    (seed-default-pages!)))

(defn ^:export init
  []
  (try
    (init-wiki!)
    (catch js/Error e
      (show-error! (str "Live init failed: " (err-message e))))
    (catch :default e
      (show-error! (str "Live init failed: " (err-message e))))))

(init)
