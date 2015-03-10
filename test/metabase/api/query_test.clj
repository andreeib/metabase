(ns metabase.api.query-test
  "Tests for /api/query endpoints."
  (:require [expectations :refer :all]
            [korma.core :refer :all]
            [metabase.db :refer :all]
            (metabase.models [query :refer [Query]]
                             [query-execution :refer [QueryExecution]])
            [metabase.test.util :refer [match-$ random-name expect-eval-actual-first]]
            [metabase.test-data :refer :all]))

;; ## Helper Fns

(defn create-query [& {:as kwargs}]
  ((user->client :rasta) :post 200 "query" (merge {:database (:id @test-db)
                                                   :sql "SELECT COUNT(*) FROM VENUES;"}
                                                  kwargs)))

;; ## POST /api/query (create)
;; Check that we can save a Query
(expect-eval-actual-first
    (match-$ (sel :one Query (order :id :DESC))
      {:database_id (:id @test-db)
       :name $
       :type "rawsql"
       :creator_id (user->id :rasta)
       :updated_at $
       :details {:timezone nil
                 :sql "SELECT COUNT(*) FROM VENUES;"}
       :id $
       :version $
       :public_perms 0
       :created_at $})
  (create-query))


;; ## GET /api/query/:id
;; Check that we can fetch details for a Query
(expect-eval-actual-first
    (match-$ (sel :one Query (order :id :DESC))
      {:id $
       :name $
       :type "rawsql"
       :creator_id (user->id :rasta)
       :updated_at $
       :details {:timezone nil
                 :sql "SELECT COUNT(*) FROM VENUES;"}
       :database_id (:id @test-db)
       :database (match-$ @test-db
                   {:created_at $
                    :engine "h2"
                    :id $
                    :details $
                    :updated_at $
                    :name "Test Database"
                    :organization_id (:id @test-org)
                    :description nil})
       :creator (match-$ (fetch-user :rasta)
                  {:common_name "Rasta Toucan"
                   :date_joined $
                   :last_name "Toucan"
                   :id $
                   :is_superuser false
                   :last_login $
                   :first_name "Rasta"
                   :email "rasta@metabase.com"})
       :can_read true
       :can_write true
       :version $
       :public_perms 0
       :created_at $})
  (let [{id :id} (create-query)]
    ((user->client :rasta) :get 200 (format "query/%d" id))))


;; ## PUT /api/query/:id
;; Check that we can update a Query
(expect-eval-actual-first
    [{:name "My Awesome Query"
      :version 2}
     {:name "My Awesome Query 2"
      :version 3}]
  (let [{:keys [id name database_id]} (create-query)
        get-query-name-and-version (fn [] (sel :one :fields [Query :name :version] :id id))]
    [(do ((user->client :rasta) :put 200 (format "query/%d" id) {:name "My Awesome Query"
                                                                 :database {:id database_id}})
         (get-query-name-and-version))
     (do ((user->client :rasta) :put 200 (format "query/%d" id) {:name "My Awesome Query 2"
                                                                 :database {:id database_id}})
         (get-query-name-and-version))]))


;; ## DELETE /api/query/:id
;; Check that we can delete a Query
(let [query-name (random-name)
      get-query-name (fn [] (sel :one :field [Query :name] :name query-name))]
  (expect-eval-actual-first
      [query-name
       nil]
    (let [{id :id} (create-query :name query-name)]
      [(get-query-name)
       (do ((user->client :rasta) :delete 204 (format "query/%d" id))
           (get-query-name))])))

;; ## POST /api/query (clone)
;; Can we clone a Query?
(let [query-name (random-name)]
  (expect-eval-actual-first
      (match-$ (sel :one Query :name (format "%s CLONED" query-name))
        {:database_id (:id @test-db)
         :name $
         :type "rawsql"
         :creator_id (user->id :crowberto)
         :updated_at $
         :details {:timezone nil
                   :sql "SELECT COUNT(*) FROM VENUES;"}
         :id $
         :version 1
         :public_perms 0
         :created_at $})
    ;; Clone Query with a different User than the one that created it
    (let [{id :id} (create-query :name query-name)]
      ((user->client :crowberto) :post 200 "query" {:clone id}))))


;; ## POST /api/query/:id & GET /api/query/:id/results
;; Can we execute a Query (i.e., create a new QueryExecution) ?
(expect-eval-actual-first
    (let [{query-id :id :as query} (sel :one Query (order :id :DESC))
          query-execution (sel :one QueryExecution :query_id query-id (order :id :DESC))]
      [(match-$ query-execution
         {:id $
          :uuid $
          :query_id query-id
          :version 1
          :status "starting"
          :started_at $})
       [(match-$ query-execution
           {:query_id query-id
            :raw_query ""
            :result_rows 1
            :finished_at $
            :started_at $
            :json_query {:native {:timezone nil
                                  :query "SELECT COUNT(*) FROM VENUES;"}
                         :database (:id @test-db)
                         :type "native"}
            :status "completed"
            :id $
            :uuid $
            :row_count 1
            :running_time $
            :version 1})]])
  (let [{id :id} (create-query)]
    [;; POST /query/:id should create a new QueryExecution
     ((user->client :rasta) :post 200 (format "query/%d" id))
     ;; GET /query/:id/results should return array of QueryExecutions for the Query (e.g., the one we just created)
     (do
       ;; wait 100ms for QueryExecution to complete. If it takes longer than that, it's probably brokesies
       (Thread/sleep 100)
       ((user->client :rasta) :get 200 (format "query/%d/results" id)))]))