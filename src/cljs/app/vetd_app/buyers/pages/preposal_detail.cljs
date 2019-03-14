(ns vetd-app.buyers.pages.preposal-detail
  (:require [vetd-app.buyers.components :as c]
            [vetd-app.ui :as ui]
            [vetd-app.docs :as docs]
            [reagent.core :as r]
            [reagent.format :as format]
            [re-frame.core :as rf]))

;; Events
(rf/reg-event-fx
 :b/nav-preposal-detail
 (fn [_ [_ preposal-idstr]]
   {:nav {:path (str "/b/preposals/" preposal-idstr)}}))

(rf/reg-event-db
 :b/route-preposal-detail
 (fn [db [_ preposal-idstr]]
   (assoc db
          :page :b/preposal-detail
          :page-params {:preposal-idstr preposal-idstr})))

;; Subscriptions
(rf/reg-sub
 :preposal-idstr
 :<- [:page-params] 
 (fn [{:keys [preposal-idstr]}] preposal-idstr))

;; Components
(defn c-preposal
  "Component to display Preposal details."
  [{:keys [id product from-org responses] :as preposal}]
  (let [pricing-estimate-value (docs/get-field-value responses "Pricing Estimate" "value" :nval)
        pricing-estimate-unit (docs/get-field-value responses "Pricing Estimate" "unit" :sval)
        pricing-estimate-details (docs/get-field-value responses "Pricing Estimate" "details" :sval)
        pricing-model (docs/get-field-value responses "Pricing Model" "value" :sval)
        free-trial? (= "yes" (docs/get-field-value responses "Do you offer a free trial?" "value" :sval))
        free-trial-terms (docs/get-field-value responses "Please describe the terms of your trial" "value" :sval)
        pitch (docs/get-field-value responses "Pitch" "value" :sval)
        employee-count (docs/get-field-value responses "Employee Count" "value" :sval)
        website (docs/get-field-value responses "Website" "value" :sval)]
    [:div.detail-container
     [:> ui/Header {:size "huge"}
      (:pname product) " " [:small " by " (:oname from-org)]]
     [:> ui/Image {:class "product-logo"
                   :src (str "https://s3.amazonaws.com/vetd-logos/" (:logo product))}]
     [c/c-rounds product]
     [c/c-categories product]
     (when free-trial? [c/c-free-trial-tag])
     [:> ui/Grid {:columns "equal"
                  :style {:margin "20px 0 0 0"}}
      [:> ui/GridRow
       [c/c-display-field {:width 10} "Pitch" pitch]]
      [:> ui/GridRow
       [c/c-display-field {:width 16} "Product Description" (:long-desc product)]]
      [:> ui/GridRow
       [c/c-display-field {:width 6} "Estimated Price"
        (if pricing-estimate-value
          [:<> (format/currency-format pricing-estimate-value) " / " pricing-estimate-unit
           (when (and pricing-estimate-details
                      (not= "" pricing-estimate-details))
             (str " - " pricing-estimate-details))]
          pricing-estimate-details)]
       (when (not= "" free-trial-terms)
         [c/c-display-field {:width 4} "Free Trial Terms" free-trial-terms])
       (when (not= "" pricing-model)
         [c/c-display-field {:width 6} "Pricing Model" pricing-model])]
      [:> ui/GridRow
       [c/c-display-field nil (str "About " (:oname from-org))
        [:<>
         (when (not= "" website)
           [:span "Website: " website [:br]])
         (when (not= "" employee-count)
           [:span "Number of Employees: " employee-count])]]]]]))

(defn c-page []
  (let [preposal-idstr& (rf/subscribe [:preposal-idstr])
        org-id& (rf/subscribe [:org-id])
        preps& (rf/subscribe [:gql/sub
                              {:queries
                               [[:docs {:dtype "preposal"
                                        :idstr @preposal-idstr&}
                                 [:id :idstr :title
                                  [:product [:id :pname :logo :short-desc :long-desc
                                             [:rounds {:buyer-id @org-id&
                                                       :status "active"}
                                              [:id :created :status]]
                                             [:categories [:id :idstr :cname]]]]
                                  [:from-org [:id :oname]]
                                  [:from-user [:id :uname]]
                                  [:to-org [:id :oname]]
                                  [:to-user [:id :uname]]
                                  [:responses
                                   [:id :prompt-id :notes
                                    [:prompt
                                     [:id :prompt]]
                                    [:fields
                                     [:id :pf-id :idx :sval :nval :dval
                                      [:prompt-field [:id :fname]]]]]]]]]}])]
    (fn []
      [:div.container-with-sidebar
       [:div.sidebar
        [:> ui/Button {:on-click #(rf/dispatch [:b/nav-preposals])
                       :color "gray"
                       :icon true
                       :labelPosition "left"}
         "All Preposals"
         [:> ui/Icon {:name "left arrow"}]]]
       [:> ui/Segment {:class "inner-container"}
        (if (= :loading @preps&)
          [:> ui/Loader {:active true :inline true}]
          [c-preposal (-> @preps& :docs first)])]])))