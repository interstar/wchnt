(ns wchnt-lang.targets.live-std
  "Live-runtime standard library facade.

   The interpreter host implementation is shared by the JVM test runner and
   the ClojureScript live runtime. This namespace is the live target's stable
   standard-library entry point; keeping the facade separate lets target
   plugins choose the live library without exposing the interpreter namespace
   as part of the target API."
  (:require [wchnt-lang.targets.interpreter-std :as interpreter-std]))

(def host-api interpreter-std/host-api)
(def known-host? interpreter-std/known-host?)
(def lookup interpreter-std/lookup)
(def query? interpreter-std/query?)
(def query-spec interpreter-std/query-spec)
(def make-maths interpreter-std/make-maths)
(def invoke interpreter-std/invoke)
