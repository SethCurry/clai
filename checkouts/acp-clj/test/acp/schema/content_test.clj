(ns acp.schema.content-test

  (:require [clojure.test :as test]
            [malli.core :as m]
            [acp.schema.content :as content]))


;; -------------------------------------------------------------------------
;; Helpers
;; -------------------------------------------------------------------------

(defn- returned
  "Return the map produced by calling `f`, or the `ExceptionInfo` it threw."
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      e)))


(test/deftest Role-schema
  (test/testing "accepts the protocol roles"
    (doseq [role ["assistant" "user"]]
      (test/is (m/validate content/Role role)
               (str "expected " (pr-str role) " to be accepted"))))

  (test/testing "rejects unknown and differently-cased roles"
    (doseq [role ["agent" "User" "system" ""]]
      (test/is (not (m/validate content/Role role))
               (str "expected " (pr-str role) " to be rejected"))))

  (test/testing "rejects non-string values"
    (doseq [value [nil 1 :user 'user true [:user] {:role "user"}]]
      (test/is (not (m/validate content/Role value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest Annotations-schema
  (test/testing "accepts an empty map"
    (test/is (m/validate content/Annotations {})))

  (test/testing "accepts audience, timestamp, priority and metadata"
    (test/is (m/validate content/Annotations {:audience ["user" "assistant"]}))
    (test/is (m/validate content/Annotations {:lastModified "2026-01-01T00:00:00Z"}))
    (test/is (m/validate content/Annotations {:priority 0.5}))
    (test/is (m/validate content/Annotations {:priority 1}))
    (test/is (m/validate content/Annotations {:_meta {:traceId "t-1"}})))

  (test/testing "accepts explicit nulls"
    (test/is (m/validate content/Annotations {:audience nil
                                              :lastModified nil
                                              :priority nil
                                              :_meta nil})))

  (test/testing "accepts extra keys (map is open)"
    (test/is (m/validate content/Annotations {:notInSpec true})))

  (test/testing "rejects malformed values"
    (test/is (not (m/validate content/Annotations {:audience ["everyone"]})))
    (test/is (not (m/validate content/Annotations {:audience "user"})))
    (test/is (not (m/validate content/Annotations {:lastModified 1})))
    (test/is (not (m/validate content/Annotations {:priority "high"})))
    (doseq [value [nil "annotations" [:annotations] true 42]]
      (test/is (not (m/validate content/Annotations value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->Annotations
  (test/testing "fills every optional slot with nil"
    (test/is (= {:audience nil :lastModified nil :priority nil :_meta nil}
                (content/->Annotations))))

  (test/testing "accepts annotations as keyword arguments"
    (test/is (m/validate content/Annotations
                        (content/->Annotations :audience ["user"] :priority 0.5))))

  (test/testing "rejects invalid annotations"
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (content/->Annotations :audience ["everyone"]))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(content/->Annotations :priority "high"))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid Annotations" (ex-message e))))))


(test/deftest TextContent-schema
  (test/testing "accepts text with optional annotations and metadata"
    (test/is (m/validate content/TextContent {:text "hello"}))
    (test/is (m/validate content/TextContent {:text ""}))
    (test/is (m/validate content/TextContent
                        {:text "hello" :annotations {:audience ["user"]}}))
    (test/is (m/validate content/TextContent {:text "hello" :_meta {:traceId "t-1"}})))

  (test/testing "accepts extra keys (map is open)"
    (test/is (m/validate content/TextContent {:text "hello" :notInSpec true})))

  (test/testing "rejects missing and malformed text"
    (test/is (not (m/validate content/TextContent {})))
    (test/is (not (m/validate content/TextContent {:text nil})))
    (test/is (not (m/validate content/TextContent {:text 1})))
    (test/is (not (m/validate content/TextContent
                              {:text "hello" :annotations {:audience ["everyone"]}})))
    (test/is (not (m/validate content/TextContent
                              {:text "hello" :annotations "user"})))
    (doseq [value [nil "hello" [:text] true 42]]
      (test/is (not (m/validate content/TextContent value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->TextContent
  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate content/TextContent (content/->TextContent "hello"))))

  (test/testing "rejects non-string text"
    (doseq [text [nil 1 1.5 :hello 'hello true [:hello] {:text "hello"}]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (content/->TextContent text))
               (str "expected text " (pr-str text) " to be rejected"))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(content/->TextContent 42))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid TextContent" (ex-message e))))))


(test/deftest ImageContent-schema
  (test/testing "accepts base64 data and a MIME type"
    (test/is (m/validate content/ImageContent {:data "aGVsbG8=" :mimeType "image/png"}))
    (test/is (m/validate content/ImageContent
                        {:data "aGVsbG8="
                         :mimeType "image/png"
                         :uri "file:///tmp/image.png"
                         :annotations {:priority 1}
                         :_meta {:traceId "t-1"}})))

  (test/testing "requires data and mimeType"
    (test/is (not (m/validate content/ImageContent {:data "aGVsbG8="})))
    (test/is (not (m/validate content/ImageContent {:mimeType "image/png"}))))

  (test/testing "rejects malformed fields"
    (test/is (not (m/validate content/ImageContent {:data 1 :mimeType "image/png"})))
    (test/is (not (m/validate content/ImageContent {:data "aGVsbG8=" :mimeType nil})))
    (test/is (not (m/validate content/ImageContent
                              {:data "aGVsbG8=" :mimeType "image/png" :uri 1}))))

  (test/testing "builds the same shape through the constructor"
    (test/is (m/validate content/ImageContent
                        (content/->ImageContent "aGVsbG8=" "image/png")))))


(test/deftest AudioContent-schema
  (test/testing "accepts base64 data and a MIME type"
    (test/is (m/validate content/AudioContent {:data "aGVsbG8=" :mimeType "audio/wav"}))
    (test/is (m/validate content/AudioContent
                        {:data "aGVsbG8="
                         :mimeType "audio/wav"
                         :annotations {:audience ["assistant"]}
                         :_meta {:traceId "t-1"}})))

  (test/testing "requires data and mimeType"
    (test/is (not (m/validate content/AudioContent {:data "aGVsbG8="})))
    (test/is (not (m/validate content/AudioContent {:mimeType "audio/wav"}))))

  (test/testing "builds the same shape through the constructor"
    (test/is (m/validate content/AudioContent
                        (content/->AudioContent "aGVsbG8=" "audio/wav")))))


(test/deftest ResourceLink-schema
  (test/testing "accepts the required name and uri"
    (test/is (m/validate content/ResourceLink {:name "notes.txt" :uri "file:///notes.txt"}))
    (test/is (m/validate content/ResourceLink
                        {:name "notes.txt"
                         :uri "file:///notes.txt"
                         :description "Notes"
                         :mimeType "text/plain"
                         :size 128
                         :title "Notes"
                         :annotations {:priority 1}
                         :_meta {:traceId "t-1"}})))

  (test/testing "requires name and uri"
    (test/is (not (m/validate content/ResourceLink {:name "notes.txt"})))
    (test/is (not (m/validate content/ResourceLink {:uri "file:///notes.txt"}))))

  (test/testing "rejects a non-integral size"
    (test/is (not (m/validate content/ResourceLink
                              {:name "notes.txt" :uri "file:///notes.txt" :size 1.5}))))

  (test/testing "builds the same shape through the constructor"
    (test/is (m/validate content/ResourceLink
                        (content/->ResourceLink "notes.txt" "file:///notes.txt")))))


(test/deftest TextResourceContents-schema
  (test/testing "accepts text and uri"
    (test/is (m/validate content/TextResourceContents {:text "hello" :uri "file:///a.txt"}))
    (test/is (m/validate content/TextResourceContents
                        {:text "hello" :uri "file:///a.txt" :mimeType "text/plain"})))

  (test/testing "requires text and uri"
    (test/is (not (m/validate content/TextResourceContents {:text "hello"})))
    (test/is (not (m/validate content/TextResourceContents {:uri "file:///a.txt"}))))

  (test/testing "rejects malformed fields"
    (test/is (not (m/validate content/TextResourceContents
                              {:text 1 :uri "file:///a.txt"})))
    (test/is (not (m/validate content/TextResourceContents
                              {:text "hello" :uri "file:///a.txt" :mimeType 1})))))


(test/deftest BlobResourceContents-schema
  (test/testing "accepts blob and uri"
    (test/is (m/validate content/BlobResourceContents {:blob "aGVsbG8=" :uri "file:///a.bin"}))
    (test/is (m/validate content/BlobResourceContents
                        {:blob "aGVsbG8=" :uri "file:///a.bin" :mimeType "application/octet-stream"})))

  (test/testing "requires blob and uri"
    (test/is (not (m/validate content/BlobResourceContents {:blob "aGVsbG8="})))
    (test/is (not (m/validate content/BlobResourceContents {:uri "file:///a.bin"}))))

  (test/testing "rejects malformed fields"
    (test/is (not (m/validate content/BlobResourceContents
                              {:blob 1 :uri "file:///a.bin"})))))


(test/deftest EmbeddedResourceResource-schema
  (test/testing "accepts text and blob resource contents"
    (test/is (m/validate content/EmbeddedResourceResource {:text "hello" :uri "file:///a.txt"}))
    (test/is (m/validate content/EmbeddedResourceResource {:blob "aGVsbG8=" :uri "file:///a.bin"})))

  (test/testing "rejects values that are neither"
    (test/is (not (m/validate content/EmbeddedResourceResource {:uri "file:///a.txt"})))
    (test/is (not (m/validate content/EmbeddedResourceResource {})))
    (doseq [value [nil "hello" [:text] true 42]]
      (test/is (not (m/validate content/EmbeddedResourceResource value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest EmbeddedResource-schema
  (test/testing "accepts a nested text or blob resource"
    (test/is (m/validate content/EmbeddedResource
                        {:resource {:text "hello" :uri "file:///a.txt"}}))
    (test/is (m/validate content/EmbeddedResource
                        {:resource {:blob "aGVsbG8=" :uri "file:///a.bin"}
                         :annotations {:priority 1}
                         :_meta {:traceId "t-1"}})))

  (test/testing "requires a valid resource"
    (test/is (not (m/validate content/EmbeddedResource {})))
    (test/is (not (m/validate content/EmbeddedResource {:resource nil})))
    (test/is (not (m/validate content/EmbeddedResource {:resource {:uri "file:///a.txt"}})))))


(test/deftest ContentBlock-schema
  (test/testing "accepts every content block variant"
    (test/is (m/validate content/ContentBlock {:type "text" :text "hello"}))
    (test/is (m/validate content/ContentBlock {:type "image" :data "aGVsbG8=" :mimeType "image/png"}))
    (test/is (m/validate content/ContentBlock {:type "audio" :data "aGVsbG8=" :mimeType "audio/wav"}))
    (test/is (m/validate content/ContentBlock {:type "resource_link" :name "a.txt" :uri "file:///a.txt"}))
    (test/is (m/validate content/ContentBlock
                        {:type "resource" :resource {:text "hello" :uri "file:///a.txt"}})))

  (test/testing "accepts metadata on each variant"
    (test/is (m/validate content/ContentBlock
                        {:type "text" :text "hello" :_meta {:traceId "t-1"}})))

  (test/testing "requires a known type discriminator"
    (test/is (not (m/validate content/ContentBlock {:text "hello"})))
    (test/is (not (m/validate content/ContentBlock {:type "video"})))
    (test/is (not (m/validate content/ContentBlock {:type "Text" :text "hello"})))
    (test/is (not (m/validate content/ContentBlock {:type nil :text "hello"}))))

  (test/testing "rejects variants missing their own fields"
    (test/is (not (m/validate content/ContentBlock {:type "text"})))
    (test/is (not (m/validate content/ContentBlock {:type "image" :data "aGVsbG8="})))
    (test/is (not (m/validate content/ContentBlock {:type "resource_link" :name "a.txt"})))
    (test/is (not (m/validate content/ContentBlock {:type "resource"}))))

  (test/testing "rejects non-map values"
    (doseq [value [nil "text" :text [:text] true 42]]
      (test/is (not (m/validate content/ContentBlock value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->ContentBlock
  (test/testing "returns a valid block unchanged"
    (test/is (= {:type "text" :text "hello"}
                (content/->ContentBlock {:type "text" :text "hello"}))))

  (test/testing "rejects invalid blocks"
    (doseq [value [{} {:type "video"} {:type "text"} {:type "image" :data "x"}]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (content/->ContentBlock value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest Content-schema
  (test/testing "accepts a wrapped content block"
    (test/is (m/validate content/Content {:content {:type "text" :text "hello"}}))
    (test/is (m/validate content/Content
                        {:content {:type "text" :text "hello"} :_meta {:traceId "t-1"}})))

  (test/testing "requires a valid content block"
    (test/is (not (m/validate content/Content {})))
    (test/is (not (m/validate content/Content {:content nil})))
    (test/is (not (m/validate content/Content {:content {:type "video"}}))))

  (test/testing "builds the same shape through the constructor"
    (let [value (content/->Content {:type "text" :text "hello"})]
      (test/is (= {:content {:type "text" :text "hello"} :_meta nil} value))
      (test/is (m/validate content/Content value)))))


(test/deftest Diff-schema
  (test/testing "accepts a path and new text"
    (test/is (m/validate content/Diff {:path "notes.txt" :newText "hello"}))
    (test/is (m/validate content/Diff {:path "notes.txt" :oldText "bye" :newText "hello"}))
    (test/is (m/validate content/Diff {:path "notes.txt" :oldText nil :newText "hello"})))

  (test/testing "accepts metadata and extra keys (map is open)"
    (test/is (m/validate content/Diff
                        {:path "notes.txt" :newText "hello" :_meta {:traceId "t-1"}}))
    (test/is (m/validate content/Diff
                        {:path "notes.txt" :newText "hello" :notInSpec true})))

  (test/testing "requires path and newText"
    (test/is (not (m/validate content/Diff {:path "notes.txt"})))
    (test/is (not (m/validate content/Diff {:newText "hello"}))))

  (test/testing "rejects malformed fields"
    (test/is (not (m/validate content/Diff {:path 1 :newText "hello"})))
    (test/is (not (m/validate content/Diff {:path "notes.txt" :newText nil})))
    (test/is (not (m/validate content/Diff {:path "notes.txt" :oldText 1 :newText "hello"}))))

  (test/testing "builds the same shape through the constructor"
    (let [value (content/->Diff "notes.txt" "hello")]
      (test/is (= {:path "notes.txt" :newText "hello" :oldText nil :_meta nil} value))
      (test/is (m/validate content/Diff value)))))
