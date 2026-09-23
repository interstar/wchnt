(ns live.core
  "Live page: wiki storage, CodeMirror, canvas/CLI harness, Run."
  (:require-macros [live.embed :refer [blank-source example-source]])
  (:require [clojure.string :as str]
            [live.editor :as editor]
            [live.nav :as nav]
            [live.render :as render]
            [live.runtime :as runtime]
            [live.storage :as storage]
            [goog.object :as gobj]))

(defonce !editor (atom nil))
(defonce !harness (atom nil))
(defonce !current-page (atom nil))
(defonce !save-timer (atom nil))
(defonce !history-silent (atom false))
(defonce !mode (atom :read))
(defonce !scroll (atom {}))
(defonce !resetting (atom false))

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

(defn- on!
  "Bind a listener only when the element exists, so markup changes can't crash
   the shell during init."
  ([id event f] (on! id event f false))
  ([id event f capture?]
   (when-let [node (el id)]
     (.addEventListener node event f capture?))))

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

(defn- set-hidden!
  [id hidden?]
  (gobj/set (el id) "hidden" hidden?))

(defn- set-run-host-ui!
  [host]
  (let [cli? (= host "cli-live")
        test? (= host "testharness-live")
        form? (= host "form")
        stage? (and (not cli?) (not test?) (not form?))]
    (set-hidden! "stage-wrap" (not stage?))
    (set-hidden! "form-wrap" (not form?))
    (set-hidden! "cli-wrap" (not cli?))
    (set-hidden! "test-wrap" (not test?))
    (set-hidden! "run-hint-canvas" (not stage?))
    (set-hidden! "run-hint-cli" (not cli?))
    (set-hidden! "run-hint-test" (not test?))))

(defn- focus-run-input!
  [host]
  (js/setTimeout
   (fn []
     (cond
       (= host "cli-live") (when-let [line (el "cli-line")] (.focus line))
       (or (= host "testharness-live") (= host "form")) nil
       :else (focus-stage-keys!)))
   0))

(defn- show-run-modal!
  [host]
  (blur-editor!)
  (set-run-host-ui! host)
  (gobj/set (el "run-modal-title") "textContent"
            (case host
              "cli-live" "Terminal"
              "testharness-live" "Unit tests"
              "Program"))
  (gobj/set (.-body js/document) "style" "overflow: hidden")
  (gobj/set (el "run-modal") "hidden" false)
  (focus-run-input! host))

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

(defn- reader-el [] (el "reader"))

(defn- remember-scroll!
  "Stash the reader scroll position for a page so returning restores it."
  [page-name]
  (when-let [r (reader-el)]
    (when (and page-name (not (.-hidden r)))
      (swap! !scroll assoc page-name (.-scrollTop r)))))

(defn- render-reader!
  [content]
  (when-let [r (reader-el)]
    (set! (.-innerHTML r) (render/render-html content))
    (set! (.-scrollTop r) (get @!scroll @!current-page 0))))

(defn- update-edit-button!
  []
  (when-let [b (el "edit-toggle")]
    (gobj/set b "textContent" (if (= @!mode :edit) "Done" "Edit"))))

(defn- show-reader!
  []
  (gobj/set (reader-el) "hidden" false)
  (gobj/set (el "editor") "hidden" true))

(defn- show-editor!
  []
  (gobj/set (reader-el) "hidden" true)
  (gobj/set (el "editor") "hidden" false)
  (when-let [cm @!editor]
    (js/setTimeout #(.refresh cm) 0)))

(defn- load-editor!
  [content]
  (when-let [ed @!editor]
    (reset! !loading-page true)
    (editor/set-text! ed content)
    (reset! !loading-page false))
  (when (= @!mode :read)
    (render-reader! content)))

(defn- set-read-mode!
  "Show the rendered read view for content."
  [content]
  (reset! !mode :read)
  (render-reader! content)
  (show-reader!)
  (update-edit-button!))

(defn- set-edit-mode!
  "Show the CodeMirror editor for the current page."
  []
  (reset! !mode :edit)
  (show-editor!)
  (update-edit-button!)
  (when-let [cm @!editor]
    (js/setTimeout #(.focus cm) 0)))

(defn- open-page-with-content!
  "Switch pages after saving the current one; store explicit content."
  [page-name content]
  (let [from-page @!current-page]
    (remember-scroll! from-page)
    (flush-save!)
    (stop!)
    (storage/save-page! page-name content)
    (reset! !current-page page-name)
    (nav/record-visit! page-name)
    (update-title! page-name)
    (load-editor! content)
    (set-read-mode! content)
    (maybe-push-history! from-page page-name)))

(defn- open-page!
  "Switch pages after saving the current one; load destination from storage."
  [page-name]
  (let [from-page @!current-page
        trimmed (str/trim page-name)
        existing (storage/get-page trimmed)
        content (or existing (storage/blank-page trimmed))]
    (remember-scroll! from-page)
    (flush-save!)
    (stop!)
    (when-not existing
      (storage/save-page! trimmed content))
    (storage/set-current-page! trimmed)
    (reset! !current-page trimmed)
    (nav/record-visit! trimmed)
    (update-title! trimmed)
    (load-editor! content)
    (set-read-mode! content)
    (maybe-push-history! from-page trimmed)))

(defn- navigate-to-page!
  [page-name]
  (if-let [resolved (storage/resolve-page-name page-name)]
    (open-page! resolved)
    (show-error! (str "No page named '" page-name "'."))))

(defn- toggle-mode!
  "Read <-> Edit for the current page. Leaving edit saves and re-renders."
  []
  (if (= @!mode :edit)
    (do (flush-save!)
        (set-read-mode! (editor/text @!editor)))
    (set-edit-mode!)))

(defn- stop!
  []
  (when-let [h @!harness]
    (.stop h))
  (hide-run-modal!)
  (set-running! false))

(defn- on-run-error
  [e]
  (stop!)
  (show-error! (err-message e)))

(defn- start-testharness!
  [result]
  (show-run-modal! "testharness-live")
  (gobj/set (el "test-transcript") "textContent" (:message result)))

(defn- start-program!
  [harness result]
  (show-run-modal! (:host result))
  (if (= "cli-live" (:host result))
    (.startCli harness (:init result) (:step result) on-run-error)
    (.start harness (:init result) (:step result) on-run-error))
  (set-running! true))

(defn- start-run!
  []
  (stop!)
  (clear-message!)
  (flush-save!)
  (try
    (let [harness @!harness
          result (runtime/prepare (editor/text @!editor)
                                  {:graphics (.-wchntGraphics harness)
                                   :form (.-wchntForm harness)
                                   :input (.-input harness)
                                   :console (.-wchntConsole harness)
                                   :maths (.-wchntMaths harness)})]
      (case (:kind result)
        :documentation (show-info! (:message result))
        :library (show-info! (:message result))
        :testharness (start-testharness! result)
        :program (start-program! harness result)))
    (catch :default e
      (on-run-error e))))

;; --- bottom sheets ----------------------------------------------------------

(defn- open-sheet! [id]
  (when-let [s (el id)] (gobj/set s "hidden" false)))

(defn- close-sheet! [id]
  (when-let [s (el id)] (gobj/set s "hidden" true)))

(defn- close-all-sheets! []
  (close-sheet! "more-sheet")
  (close-sheet! "pages-sheet"))

;; --- create page ------------------------------------------------------------

(defn- create-page!
  "Create a blank page (failing if it exists) and drop straight into edit mode."
  [name]
  (let [trimmed (str/trim (or name ""))]
    (try
      (cond
        (str/blank? trimmed) (show-error! "Enter a page name")
        (storage/resolve-page-name trimmed)
        (show-error! (str "Page '" trimmed "' already exists."))
        :else
        (do (open-page-with-content! trimmed (storage/blank-page trimmed))
            (set-edit-mode!)
            (clear-message!)))
      (catch :default e (show-error! (err-message e))))))

(defn- on-new-page! []
  (close-all-sheets!)
  (when-let [name (js/prompt "New page name:")]
    (create-page! name)))

;; --- menu rows (shared by search + pages sheet) -----------------------------

(defn- clear-children! [node]
  (set! (.-innerHTML node) ""))

(defn- menu-item
  "A full-width row button for the search dropdown / pages sheet."
  [label cls on-click]
  (let [b (.createElement js/document "button")]
    (set! (.-type b) "button")
    (set! (.-className b) (str "menu-item" (when cls (str " " cls))))
    (set! (.-textContent b) label)
    (.addEventListener b "click" on-click)
    b))

(defn- menu-heading
  [text]
  (let [d (.createElement js/document "div")]
    (set! (.-className d) "pages-section-label search-section-label")
    (set! (.-textContent d) text)
    d))

(defn- fill-menu!
  [container-id names empty-label on-pick]
  (when-let [c (el container-id)]
    (clear-children! c)
    (if (empty? names)
      (.appendChild c (menu-item empty-label "empty" (fn [_])))
      (doseq [name names]
        (.appendChild c (menu-item name nil (fn [_] (on-pick name))))))))

;; --- search (top bar) -------------------------------------------------------

(defn- hide-search-results! []
  (when-let [r (el "search-results")]
    (gobj/set r "hidden" true)
    (clear-children! r)))

(defn- clear-search-input! []
  (when-let [i (el "search-input")] (set! (.-value i) "")))

(defn- choose-search! [name]
  (hide-search-results!)
  (clear-search-input!)
  (navigate-to-page! name))

(defn- create-from-search! [name]
  (hide-search-results!)
  (clear-search-input!)
  (create-page! name))

(defn- append-search-hits!
  [r names]
  (doseq [name names]
    (.appendChild r (menu-item name nil (fn [_] (choose-search! name))))))

(defn- render-search-results!
  [q]
  (when-let [r (el "search-results")]
    (clear-children! r)
    (let [trimmed (str/trim q)
          {:keys [names bodies]} (nav/search-pages q)
          querying? (seq trimmed)]
      (when (and querying? (seq names))
        (.appendChild r (menu-heading "Page names")))
      (append-search-hits! r names)
      (when (and querying? (seq bodies))
        (.appendChild r (menu-heading "In page text"))
        (append-search-hits! r bodies))
      (when (and querying? (not (nav/exact-page? trimmed)))
        (.appendChild r (menu-item (str "Create \"" trimmed "\"") "create"
                                   (fn [_] (create-from-search! trimmed)))))
      (when (and (empty? names) (empty? bodies) (not querying?))
        (.appendChild r (menu-item "No pages yet" "empty" (fn [_]))))
      (gobj/set r "hidden" false))))

(defn- on-search-input! [_]
  (render-search-results! (.-value (el "search-input"))))

(defn- on-search-key! [evt]
  (case (.-key evt)
    "Escape" (do (hide-search-results!) (.blur (el "search-input")))
    "Enter" (let [q (str/trim (.-value (el "search-input")))]
              (.preventDefault evt)
              (when (seq q)
                (if (nav/exact-page? q)
                  (choose-search! q)
                  (create-from-search! q))))
    nil))

;; --- pages sheet (recents + backlinks + all) --------------------------------

(defn- pick-page! [name]
  (close-all-sheets!)
  (navigate-to-page! name))

(defn- render-pages-sheet!
  [filter-q]
  (let [blank? (str/blank? filter-q)
        recents (when blank? (nav/recents))
        backs (when (and blank? @!current-page) (nav/backlinks @!current-page))]
    (gobj/set (el "pages-recent-section") "hidden" (not (seq recents)))
    (when (seq recents) (fill-menu! "pages-recent" recents "—" pick-page!))
    (gobj/set (el "pages-backlinks-section") "hidden" (not (seq backs)))
    (when (seq backs) (fill-menu! "pages-backlinks" backs "—" pick-page!))
    (fill-menu! "pages-all" (nav/search filter-q) "No matching pages" pick-page!)))

(defn- open-pages-sheet! []
  (close-sheet! "more-sheet")
  (when-let [f (el "pages-filter")] (set! (.-value f) ""))
  (render-pages-sheet! "")
  (open-sheet! "pages-sheet")
  (js/setTimeout #(when-let [f (el "pages-filter")] (.focus f)) 0))

(defn- on-pages-filter! [_]
  (render-pages-sheet! (.-value (el "pages-filter"))))

;; --- back / swipe -----------------------------------------------------------

(defn- go-back! []
  (close-all-sheets!)
  (.back js/history))

(defonce !touch (atom nil))

(defn- on-touch-start! [evt]
  (when-let [t (aget (.-touches evt) 0)]
    (reset! !touch {:x (.-clientX t) :y (.-clientY t) :t (.now js/Date)})))

(defn- on-touch-end! [evt]
  (when-let [start @!touch]
    (when-let [t (aget (.-changedTouches evt) 0)]
      (let [dx (- (.-clientX t) (:x start))
            dy (- (.-clientY t) (:y start))
            dt (- (.now js/Date) (:t start))]
        (when (and (< (:x start) 40)          ; began at the left edge
                   (> dx 80)                  ; travelled right
                   (< (js/Math.abs dy) 50)    ; stayed horizontal
                   (< dt 600))                ; was a flick, not a drag
          (go-back!))))
    (reset! !touch nil)))

(defn- on-load-example!
  []
  (let [example (js/prompt
                 "Example (bounce_canvas, square_canvas, pollution_canvas, adventure_cli, writepaths, flyingA, flyingB, factory_args, combinators_cli, maths_cli, origin_canvas):"
                 "writepaths")]
    (when example
      (let [name (str/trim example)
            content (case name
                      "bounce_canvas" (example-source "bounce_canvas")
                      "square_canvas" (example-source "square_canvas")
                      "pollution_canvas" (example-source "pollution_canvas")
                      "adventure_cli" (example-source "adventure_cli")
                      "writepaths" (example-source "writepaths")
                      "flyingA" (example-source "flyingA")
                      "flyingB" (example-source "flyingB")
                      "factory_args" (example-source "factory_args")
                      "combinators_cli" (example-source "combinators_cli")
                      "maths_cli" (example-source "maths_cli")
                      "origin_canvas" (example-source "origin_canvas")
                      nil)]
        (if content
          (open-page-with-content! name content)
          (show-error! (str "Unknown example '" name "'")))))))

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
  "Clear all saved wiki pages and reload. Destructive; callers confirm first.
   Skip the beforeunload/pagehide save so the page we were editing is not
   written back over the empty store."
  []
  (reset! !resetting true)
  (cancel-scheduled-save!)
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
  (when-let [r (el "reader")]
    (set! (.-className r) (str "cm-s-" (cm-theme theme))))
  (when-let [btn (el "theme-toggle")]
    (gobj/set btn "textContent" (if (= theme "light") "Dark" "Light"))))

(defn- on-theme-toggle!
  []
  (let [next (if (= (current-theme) "light") "dark" "light")]
    (.setItem js/localStorage theme-key next)
    (apply-theme! next)))

(defn- bind-reader-links! []
  (.addEventListener (reader-el) "click"
                     (fn [evt]
                       (when-let [target (.-target evt)]
                         (when-let [a (.closest target "[data-wiki-page]")]
                           (.preventDefault evt)
                           (close-all-sheets!)
                           (navigate-to-page! (.getAttribute a "data-wiki-page")))))))

(defn- bind-sheet-close! []
  (let [nodes (.querySelectorAll js/document "[data-sheet-close]")]
    (dotimes [i (.-length nodes)]
      (.addEventListener (aget nodes i) "click" (fn [_] (close-all-sheets!))))))

(defn- bind-dismiss-search! []
  ;; Any click outside the search box collapses the results dropdown.
  (.addEventListener js/document "click"
                     (fn [evt]
                       (when-let [t (.-target evt)]
                         (when-not (.closest t ".header-search")
                           (hide-search-results!))))))

(defn- bind-swipe-back! []
  (let [opts #js {:passive true}]
    (.addEventListener js/document "touchstart" on-touch-start! opts)
    (.addEventListener js/document "touchend" on-touch-end! opts)))

(defn- bind-ui!
  []
  (.addEventListener js/window "popstate" on-popstate!)
  (.addEventListener js/window "keydown" on-reset-shortcut! true)
  (on! "run" "click" start-run!)
  (on! "edit-toggle" "click" toggle-mode!)
  (on! "close-run" "click" stop!)
  (on! "nav-back" "click" (fn [_] (go-back!)))
  (on! "nav-pages" "click" (fn [_] (open-pages-sheet!)))
  (on! "nav-more" "click" (fn [_] (open-sheet! "more-sheet")))
  (on! "new-page" "click" (fn [_] (on-new-page!)))
  (on! "load-example" "click" (fn [_] (close-all-sheets!) (on-load-example!)))
  (on! "export-wiki" "click" (fn [_] (close-all-sheets!) (on-export-wiki!)))
  (on! "import-wiki" "click" (fn [_] (on-import-wiki!)))
  (on! "wiki-import-file" "change" on-import-file-selected!)
  (on! "theme-toggle" "click" on-theme-toggle!)
  (on! "search-input" "input" on-search-input!)
  (on! "search-input" "focus" on-search-input!)
  (on! "search-input" "keydown" on-search-key!)
  (on! "pages-filter" "input" on-pages-filter!)
  (bind-reader-links!)
  (bind-sheet-close!)
  (bind-dismiss-search!)
  (bind-swipe-back!))

(defn- persist-on-exit!
  []
  (when-not @!resetting
    (flush-save!)))

(defn- fetch-seed-index
  "Names from seed/index.txt (written by seed-from-live.sh)."
  []
  (-> (js/fetch "seed/index.txt" #js {:cache "no-store"})
      (.then (fn [resp]
               (if (.-ok resp)
                 (.text resp)
                 (throw (js/Error. (str "seed index failed: " (.-status resp)))))))
      (.then (fn [text]
               (->> (str/split-lines (or text ""))
                    (map str/trim)
                    (remove str/blank?)
                    vec)))
      (.catch (fn [_] ["welcome"]))))

(defn- seedable?
  "A page may be seeded when it is absent, blank, or still the default
  'Write prose here' placeholder. Real user content is never overwritten."
  [name]
  (let [content (storage/get-page name)]
    (or (str/blank? content)
        (= content (storage/blank-page name))
        (and (= name "welcome") (= content (blank-source))))))

(defn- seed-page!
  "Fetch a default page from live/public/seed/ and save it — but only when the
  page is seedable (absent, blank, or the default placeholder), so user data is
  never overwritten. Falls back to a compiled-in welcome when the fetch fails."
  [name]
  (when (seedable? name)
    (-> (js/fetch (str "seed/" name ".wcn") #js {:cache "no-store"})
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.text resp)
                   (throw (js/Error. (str "seed fetch failed: " (.-status resp)))))))
        (.then (fn [text]
                 (when (seedable? name)
                   (storage/save-page! name text)
                   (when (= name @!current-page)
                     (load-editor! text)))))
        (.catch (fn [_]
                  (when (and (= name "welcome") (seedable? name))
                    (storage/save-page! name (blank-source))
                    (when (= name @!current-page)
                      (load-editor! (blank-source)))))))))

(defn- init-wiki-with-seeds!
  [seed-names]
  (let [editor-el (el "editor")
        canvas (el "stage")
        keys-el (el "stage-keys")
        harness (.create js/WCHNTHarness canvas keys-el
                         (el "cli-transcript") (el "cli-line")
                         (el "form-stage"))
        theme (current-theme)
        seed-set (set seed-names)
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
                  (contains? seed-set start-page))
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
    (set-read-mode! start-content)
    (replace-page-history! start-page)
    (reset! !history-silent false)
    (.on @!editor "change" (fn [_ _] (schedule-save!)))
    (.addEventListener js/window "beforeunload" (fn [_] (persist-on-exit!)))
    (.addEventListener js/window "pagehide" (fn [_] (persist-on-exit!)))
    (set-running! false)
    (bind-ui!)
    ;; Hidden developer helper: window.wchntReset() clears pages and reloads.
    (gobj/set js/window "wchntReset" reset-wiki!)
    (doseq [name seed-names]
      (seed-page! name))))

(defn- init-wiki!
  []
  (when-not (storage/storage-available?)
    (show-error!
     (str "Wiki storage unavailable — pages will not persist after reload. "
          "Serve live/public over http:// (e.g. python3 -m http.server) "
          "instead of opening index.html as file://.")))
  (.then (fetch-seed-index) init-wiki-with-seeds!))

(defn ^:export init
  []
  (try
    (init-wiki!)
    (catch js/Error e
      (show-error! (str "Live init failed: " (err-message e))))
    (catch :default e
      (show-error! (str "Live init failed: " (err-message e))))))

(init)
