;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.layers
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.shape-icon :as si]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

;; --- Layer Name

(def shape-for-rename-ref
  (l/derived (l/in [:workspace-local :shape-for-rename]) st/state))

(mf/defc layer-name
  [{:keys [shape on-start-edit on-stop-edit] :as props}]
  (let [local            (mf/use-state {})
        shape-for-rename (mf/deref shape-for-rename-ref)
        name-ref         (mf/use-ref)

        start-edit (fn []
                     (on-start-edit)
                     (swap! local assoc :edition true))

        accept-edit (fn []
                      (let [name-input (mf/ref-val name-ref)
                            name       (dom/get-value name-input)]
                        (on-stop-edit)
                        (swap! local assoc :edition false)
                        (st/emit! (dw/end-rename-shape)
                                  (when-not (str/empty? (str/trim name))
                                    (dw/update-shape (:id shape) {:name (str/trim name)})))))

        cancel-edit (fn []
                      (on-stop-edit)
                      (swap! local assoc :edition false)
                      (st/emit! (dw/end-rename-shape)))

        on-key-down (fn [event]
                      (when (kbd/enter? event) (accept-edit))
                      (when (kbd/esc? event) (cancel-edit)))]

    (mf/with-effect [shape-for-rename]
      (when (and (= shape-for-rename (:id shape))
                 (not (:edition @local)))
        (start-edit)))

    (mf/with-effect [(:edition @local)]
      (when (:edition @local)
        (let [name-input (mf/ref-val name-ref)]
          (dom/select-text! name-input)
          nil)))

    (if (:edition @local)
      [:input.element-name
       {:type "text"
        :ref name-ref
        :on-blur accept-edit
        :on-key-down on-key-down
        :auto-focus true
        :default-value (:name shape "")}]
      [:span.element-name
       {:ref name-ref
        :on-double-click start-edit}
       (:name shape "")
       (when (seq (:touched shape)) " *")])))

(defn- make-collapsed-iref
  [id]
  #(-> (l/in [:expanded id])
       (l/derived refs/workspace-local)))

(mf/defc layer-item
  [{:keys [index item selected objects] :as props}]
  (let [id         (:id item)
        selected?  (contains? selected id)
        container? (or (cph/frame-shape? item)
                       (cph/group-shape? item))

        disable-drag (mf/use-state false)

        expanded-iref (mf/use-memo
                       (mf/deps id)
                       (make-collapsed-iref id))

        expanded? (mf/deref expanded-iref)

        toggle-collapse
        (fn [event]
          (dom/stop-propagation event)
          (if (and expanded? (kbd/shift? event))
            (st/emit! dwc/collapse-all)
            (st/emit! (dwc/toggle-collapse id))))

        toggle-blocking
        (fn [event]
          (dom/stop-propagation event)
          (if (:blocked item)
            (st/emit! (dw/update-shape-flags [id] {:blocked false}))
            (st/emit! (dw/update-shape-flags [id] {:blocked true})
                      (dw/deselect-shape id))))

        toggle-visibility
        (fn [event]
          (dom/stop-propagation event)
          (if (:hidden item)
            (st/emit! (dw/update-shape-flags [id] {:hidden false}))
            (st/emit! (dw/update-shape-flags [id] {:hidden true}))))

        select-shape
        (fn [event]
          (dom/prevent-default event)
          (let [id (:id item)]
            (cond
              (kbd/shift? event)
              (st/emit! (dw/shift-select-shapes id))

              (or (kbd/ctrl? event) (kbd/meta? event))
              (st/emit! (dw/select-shape id true))

              (> (count selected) 1)
              (st/emit! (dw/select-shape id))
              :else
              (st/emit! (dw/select-shape id)))))

        on-context-menu
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (let [pos (dom/get-client-position event)]
            (st/emit! (dw/show-shape-context-menu {:position pos
                                                   :shape item}))))

        on-drag
        (fn [{:keys [id]}]
          (when (not (contains? selected id))
            (st/emit! (dw/select-shape id))))

        on-drop
        (fn [side _data]
          (if (= side :center)
            (st/emit! (dw/relocate-selected-shapes (:id item) 0))
            (let [to-index  (if (= side :top) (inc index) index)
                  parent-id (cph/get-parent-id objects (:id item))]
              (st/emit! (dw/relocate-selected-shapes parent-id to-index)))))

        on-hold
        (fn []
          (when-not expanded?
            (st/emit! (dwc/toggle-collapse (:id item)))))

        [dprops dref] (hooks/use-sortable
                       :data-type "penpot/layer"
                       :on-drop on-drop
                       :on-drag on-drag
                       :on-hold on-hold
                       :disabled @disable-drag
                       :detect-center? container?
                       :data {:id (:id item)
                              :index index
                              :name (:name item)})]

    (mf/use-effect
     (mf/deps selected? selected)
     (fn []
       (let [single? (= (count selected) 1)
             node (mf/ref-val dref)

             subid
             (when (and single? selected?)
               (ts/schedule
                100
                #(dom/scroll-into-view! node #js {:block "nearest", :behavior "smooth"})))]

         #(when (some? subid)
            (rx/dispose! subid)))))

    [:li {:on-context-menu on-context-menu
          :ref dref
          :class (dom/classnames
                  :component (not (nil? (:component-id item)))
                  :masked (:masked-group? item)
                  :dnd-over (= (:over dprops) :center)
                  :dnd-over-top (= (:over dprops) :top)
                  :dnd-over-bot (= (:over dprops) :bot)
                  :selected selected?
                  :type-frame (= :frame (:type item)))}

     [:div.element-list-body {:class (dom/classnames :selected selected?
                                                     :icon-layer (= (:type item) :icon))
                              :on-click select-shape
                              :on-double-click #(dom/stop-propagation %)}
      [:& si/element-icon {:shape item}]
      [:& layer-name {:shape item
                      :on-start-edit #(reset! disable-drag true)
                      :on-stop-edit #(reset! disable-drag false)}]

      [:div.element-actions {:class (when (:shapes item) "is-parent")}
       [:div.toggle-element {:class (when (:hidden item) "selected")
                             :on-click toggle-visibility}
        (if (:hidden item) i/eye-closed i/eye)]
       [:div.block-element {:class (when (:blocked item) "selected")
                            :on-click toggle-blocking}
        (if (:blocked item) i/lock i/unlock)]]

      (when (:shapes item)
        [:span.toggle-content
         {:on-click toggle-collapse
          :class (when expanded? "inverse")}
         i/arrow-slide])]
     (when (and (:shapes item) expanded?)
       [:ul.element-children
        (for [[index id] (reverse (d/enumerate (:shapes item)))]
          (when-let [item (get objects id)]
            [:& layer-item
             {:item item
              :selected selected
              :index index
              :objects objects
              :key (:id item)}]))])]))

;; This components is a piece for sharding equality check between top
;; level frames and try to avoid rerender frames that are does not
;; affected by the selected set.

(mf/defc frame-wrapper
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % (mf/check-props ["selected" "item" "index" "objects"]))
              #(mf/deferred % ts/idle-then-raf)]}
  [props]
  [:> layer-item props])

(mf/defc layers-tree
  {::mf/wrap [#(mf/memo % =)]}
  [{:keys [objects] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        selected (hooks/use-equal-memo selected)
        root (get objects uuid/zero)]
    [:ul.element-list
     [:& hooks/sortable-container {}
       (for [[index id] (reverse (d/enumerate (:shapes root)))]
         (when-let [obj (get objects id)]
           (if (= (:type obj) :frame)
             [:& frame-wrapper
              {:item obj
               :selected selected
               :index index
               :objects objects
               :key id}]
             [:& layer-item
              {:item obj
               :selected selected
               :index index
               :objects objects
               :key id}])))]]))

(defn- strip-obj-data [obj]
  (select-keys obj [:id
                    :name
                    :blocked
                    :hidden
                    :shapes
                    :type
                    :content
                    :parent-id
                    :component-id
                    :component-file
                    :shape-ref
                    :touched
                    :metadata
                    :masked-group?
                    :bool-type]))

(defn- strip-objects
  "Remove unnecesary data from objects map"
  [objects]
  (persistent!
   (->> objects
        (reduce-kv
         (fn [res id obj]
           (assoc! res id (strip-obj-data obj)))
         (transient {})))))

(mf/defc layers-tree-wrapper
  {::mf/wrap-props false
   ::mf/wrap [mf/memo #(mf/throttle % 200)]}
  [props]
  (let [objects (-> (obj/get props "objects")
                    (hooks/use-equal-memo))
        objects (mf/use-memo
                 (mf/deps objects)
                 #(strip-objects objects))]
    [:& layers-tree {:objects objects}]))

;; --- Layers Toolbox

(mf/defc layers-toolbox
  {:wrap [mf/memo]}
  []
  (let [page  (mf/deref refs/workspace-page)
        focus (mf/deref refs/workspace-focus-selected)
        objects (hooks/with-focus-objects (:objects page) focus)
        title (when (= 1 (count focus)) (get-in objects [(first focus) :name]))
        
        on-scroll
        (fn [event]
          (let [target (dom/get-target event)
                target-top (:top (dom/get-bounding-rect target))
                frames (dom/get-elements-by-class "type-frame")
                last-hidden-frame (->> frames
                                       (filter #(< (- (:top (dom/get-bounding-rect %)) target-top) 0))
                                       last)]            
            (doseq [frame frames]              
              (dom/remove-class! frame "sticky"))
            
            (when last-hidden-frame
              (dom/add-class! last-hidden-frame "sticky"))))]
    
    [:div#layers.tool-window
     (if (d/not-empty? focus)
       [:div.tool-window-bar
        [:div.focus-title
         [:button.back-button
          {:on-click #(st/emit! (dw/toggle-focus-mode))}
          i/arrow-slide ]
         [:span (or title (tr "workspace.focus.selection"))]
         [:div.focus-mode (tr "workspace.focus.focus-mode")]]]

       [:div.tool-window-bar
        [:span (:name page)]])

     [:div.tool-window-content {:on-scroll on-scroll}
      [:& layers-tree-wrapper {:key (:id page)
                               :objects objects}]]]))
