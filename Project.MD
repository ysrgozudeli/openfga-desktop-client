Hi Claude,

I want you to generate a complete Java 21 + Maven + JavaFX desktop application (Windows-friendly) that works with my locally running OpenFGA server. The app should let me start from scratch: create/delete stores, apply an authorization model from DSL, then write tuples and run check queries from the UI.

Environment

* Java: 21
* Build: Maven
* UI: JavaFX (controls)
* Target OS: Windows (but should work cross-platform)
* OpenFGA server: running locally. I will provide the base URL (e.g., [http://localhost:18080](http://localhost:18080))

Key UX Goals

* I do NOT want to manually convert DSL to JSON. The UI should accept the OpenFGA DSL model text (the “model schema 1.1 … type … relations …” format) and apply it to OpenFGA.
* Internally the app may compile/transform DSL -> JSON before calling OpenFGA, but this must be hidden from the user.
* The UI should show clear request/response output and errors.

Functional Requirements

1. Configuration Panel (top of app)

* Fields:

  * OpenFGA API base URL (default: [http://localhost:18080](http://localhost:18080))
  * Optional Bearer token (empty by default)
* Store selection:

  * “List Stores” button
  * Stores shown in a dropdown (store name + id)
  * A “Refresh” action is fine

2. Store Management

* Button: “Create Store”

  * Input: store name
  * Output: created store id
  * After creation, auto-select the new store
* Button: “Delete Selected Store” (hard reset)

  * Confirm dialog required (because it deletes everything in the store)
  * After deletion, refresh store list

3. Authorization Model Management (DSL)

* Large TextArea for DSL model input
* Buttons:

  * “Validate DSL” (compile DSL locally and show any parse/compile errors without calling server)
  * “Apply Model” (compile DSL -> JSON and call WriteAuthorizationModel on the selected store)
* Outputs:

  * Show returned authorization_model_id
  * Keep track of the latest model id for subsequent operations (tuples/check)
* UI should optionally show the generated JSON (read-only) in a collapsible panel for debugging (nice-to-have)

4. Tuple Management

* Tab or section: “Tuples”
* Support writing tuples and deleting tuples.
* UI fields:

  * user (e.g., user:alice, team:team1#member, etc.)
  * relation (e.g., viewer)
  * object (e.g., carefile:123)
  * optional condition name (e.g., role_match)
  * optional condition context JSON (e.g., {"required_role":"Hulpverlener"})
* Buttons:

  * “Write Tuple”
  * “Delete Tuple”
* Show the OpenFGA response (and any errors) in an output panel.

5. Check (Authorization Query)

* Tab or section: “Check”
* UI fields:

  * user
  * relation
  * object
  * optional context JSON (e.g., {"user_role":"Hulpverlener"})
* Button: “Check”
* Output:

  * allowed = true/false
  * optionally show resolution/debug info if available

6. Example Model (for testing)
   Please include an example DSL model in the app as the default content of the DSL editor:

model
schema 1.1

condition role_match(required_role: string, user_role: string) {
required_role == user_role
}

type user

type tenant
relations
define admin: [user]
define member: [user]

type team
relations
define tenant: [tenant]
define member: [user]

type role
relations
define assignee: [user]

type carefile
relations
define tenant: [tenant]
define direct_viewer: [user, user with role_match, team#member]
define global_viewer: [user]
define escalated_viewer: [user]
define viewer: direct_viewer or global_viewer or escalated_viewer

type carefile_service
relations
define tenant: [tenant]
define carefile: [carefile]
define direct_viewer: [user, user with role_match, team#member]
define escalated_viewer: [user]
define carefile_viewer: viewer from carefile
define viewer: direct_viewer or escalated_viewer or carefile_viewer

7. Implementation Requirements

* Use OpenFGA Java SDK where possible for API calls.
* For DSL -> JSON transformation, use OpenFGA’s language/DSL transformer library (the same idea as what the CLI does), so the user never has to manually convert.
* Use Java’s HttpClient only if absolutely necessary, but prefer the SDK.
* Do not block the JavaFX UI thread. All network and compilation operations should run off the UI thread (Task/CompletableFuture) and update the UI safely.
* Provide:

  * Full Maven pom.xml
  * All Java source files
  * Instructions to run: mvn clean javafx:run (or another simple command)
  * Minimal but clean UI layout (Tabs: Stores, Model, Tuples, Check)
  * Robust error handling: show HTTP status + error body when requests fail
* Make sure it works against a local OpenFGA server started separately.

Deliverables

* A complete, runnable JavaFX Maven project layout (pom + source code) ready to copy/paste into a folder and run.
* Clear steps on how to build/run, and how to test:

  * Create store
  * Apply model
  * Write a tuple with condition context
  * Run a check with request context

Thanks!
