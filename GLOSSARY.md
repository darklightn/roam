# Glossary

## Trip Request (行程单)

The employee's business-travel request and root business record for one trip, covering its approval, planning, quotes, bookings, changes, and cancellations. User inputs and messages may create or update a Trip Request; planning is performed for the Trip Request, not separately for each message.

## Travel Plan (规划方案)

The complete, versioned planning result for a Trip Request. A successful planning run produces all four views: overall best, lowest cost, shortest duration, and closest match to personal preferences. Planning may run in parallel with Trip Request approval. The planning Agent receives the applicable Budget Ceiling and aims to produce options within it, while BookingAgent enforces the ceiling before order submission. A Travel Plan is distinct from the Trip Request and from confirmed orders.

## Itinerary Option

A concrete candidate sequence of travel segments, stays, and related timing presented in a Travel Plan. An employee selects an Itinerary Option before its associated Quote can be used to create a Booking Order.

## Budget Ceiling

The maximum travel budget applicable to a user based on their job level. PlanAgent uses it as a planning constraint, and BookingAgent performs a hard check against it before submitting a Booking Order. An over-budget order is blocked and reported with a reason; MasterAgent submits it for human budget-exception approval rather than allowing automatic submission.

## Budget Exception Decision

An authorized human's approval or rejection of a specific Booking Order that exceeds the user's Budget Ceiling. Approval permits only that order to proceed and does not approve or alter the Trip Request itself.

## Quote

A time-bounded travel option and current price and availability returned by a supplier for comparison before a booking is executed. It belongs to an Itinerary Option and may need to be refreshed when the employee wants current supplier information; refreshing a Quote is separate from Trip Request approval and does not re-evaluate the Trip Reason.

## Booking Order

A supplier-facing reservation or ticket operation associated with a Trip Request and its selected Itinerary Option or Quote. Before submission, BookingAgent enforces the applicable Budget Ceiling. If the order exceeds the ceiling, BookingAgent pauses and returns a failure with the reason; MasterAgent then submits a budget-exception approval request. If approved, MasterAgent resumes the paused workflow and BookingAgent may submit that order. This decision authorizes only the specific order and does not change the Trip Request's approval result.

## Trip Request Approval (行程单审批)

The approval of a Trip Request, focused on whether its required travel details are present and whether its Trip Reason contains content. In the MVP, required information is the employee as traveler, origin, destination, departure date, a return date or explicit one-way selection, all destinations and corresponding dates for a multi-destination trip, and the Trip Reason. Companion information is not required; flight or hotel preferences, quotes, and budget are not approval requirements. Every Trip Request is treated as a business trip in the MVP; mixed business/personal travel is not a modeled approval case. Trip Request approval runs in parallel with planning and does not evaluate budget. When required information is missing, the system first searches the employee's maintained long-term preferences and historical Trip Request information, presents any extracted values to the employee for confirmation, and never silently assumes that a prior value applies to the current trip. If no value is found, or required information remains missing after the employee reviews the extracted values, the system asks the employee directly. Approval cannot proceed until all required information is complete. The MVP does not require semantic classification or a complex quality assessment of the Trip Reason; after the employee confirms any recovered values and the required information is complete, a non-empty Trip Reason makes the request eligible for automatic approval. Updating the Trip Reason alone does not trigger re-approval. If automatic evaluation rejects a complete request, the employee may request Administrator review. The reviewed request version is locked while that review is pending; the employee must cancel it before editing. An Administrator's approval or rejection requires a reason, and rejection leaves the request unapproved until the employee edits and submits a new version.

## Trip Reason (出差理由)

The employee's explanation of why the business trip is needed. It is required for Trip Request approval and only needs to contain at least one non-whitespace character in the MVP; no fixed category or complex business-relevance explanation is required. It is not a budget justification or a description of booking preferences.

## Long-term Preference (长期偏好)

A maintained employee-specific tendency relevant across trips, such as a commonly used origin or flight and hotel preferences. The system may use it to suggest values when a current Trip Request is incomplete, but a value shown or entered by the employee for the current request takes precedence. A suggested value is not applied to the current Trip Request until the employee confirms it. The system maintains these preferences over time based on explicitly stated long-term preferences or consistent patterns across repeated choices; a one-off destination or date from a past Trip Request is historical trip information, not automatically a long-term preference.

## Historical Trip Information (历史行程信息)

Facts from the employee's past Trip Requests, such as destinations and travel dates. When the current Trip Request is incomplete, the system may present relevant past values as suggestions, but the employee must confirm or change them before they become current request information. Current-request values take precedence over both historical trip information and long-term preferences.

## Approval Record

The durable business record produced only after a Trip Request is approved. Under the MVP Trip Request policy, approval is automatic once the required information is complete, the employee has confirmed any recovered values, and the Trip Reason contains at least one non-whitespace character. Its existence means the Trip Request approval succeeded; it is evidence of that approval and its basis, not a record of an incomplete approval. Budget compliance is checked separately before booking. Changes to core itinerary information (traveler, origin, destination, dates, or itinerary segments) require re-approval; changing the Trip Reason alone does not. Prior Approval Records remain historical, and a new one is produced only when the revised request is complete and the new approval succeeds.

## Payment Confirmation

The explicit user authorization required before real money is charged or a payment commitment is made. It is separate from permission to submit a booking order and is outside the MVP execution flow; the MVP may only record an optional supplier payment-status callback. When a supplier returns a payment link on order creation, the system hands that link to the user to complete payment manually; it never completes payment on the user's behalf.

## Workflow Orchestrator

The coordination role carried out by the `MasterAgent`; this project uses `MasterAgent` as the name of the component. It decides and dispatches work, while dedicated persistence components own conversation, session, workflow, and audit records.

## MasterAgent

The entry Agent and durable coordinator for a prepared employee request. It decides which child Agents to invoke, distributes their work, coordinates cross-Agent workflow, and handles retries and recovery. It coordinates HITL, including submitting manual-approval requests after a responsible Agent reports a decision requiring human review, and handles workflow pause/resume. It does not make business approval decisions, determine the appropriate approver, or own or directly mutate the Trip Request or Approval Record lifecycle; ItineraryManageAgent owns those states.

## ItineraryManageAgent

The domain Agent that owns the Trip Request lifecycle and its approval process, including creation, updates, approval, exception-review requests, and recording approval outcomes. It is invoked and coordinated by MasterAgent but owns its domain state. Planning and Trip Request approval may proceed in parallel. Budget enforcement before booking belongs to BookingAgent, not Trip Request approval.

## Agent

A bounded capability that performs a travel-domain task through a versioned contract and permitted tools; it does not directly mutate another Agent's state.

## Intent

One requested business outcome extracted from an employee's prepared request, such as planning a flight or reserving a hotel. A request may contain multiple Intents that the MasterAgent can coordinate independently or in dependency order.

## Intent Package

The validated structured result of request preparation. It contains an `intents[]` collection and the normalized fields needed by the MasterAgent; Java validation must succeed before the package is dispatched for execution.

## Human-in-the-loop (HITL)

A durable pause in which the MasterAgent coordinates an interaction with an Administrator after the responsible business capability has identified that a human decision is required. In the MVP, every Administrator is eligible; all Administrators can see an unclaimed review, and the first to claim it owns the review. The review remains pending with reminders when nobody claims it; it is never auto-approved or auto-rejected. The reviewed request version is locked until the review is cancelled or decided. MasterAgent handles interaction and workflow pause/resume, but does not determine the Administrator or make the business decision.

## ReAct

An execution pattern in which an Agent reasons about its current task, selects an allowed Tool, observes the result, and repeats until it can return a result or a blocked/failed outcome. ReAct stays inside the task assigned by the MasterAgent.

## Agent-as-Tool

An invocation model in which the MasterAgent treats a child Agent as a callable Tool. The child Agent still runs as an independent ReAct Agent with its own tools and context; sibling Agents do not share context or call one another by default.

## Employee (普通职员)

The traveler role. An employee reads and mutates only their own Trip Request, Travel Plan, Itinerary Option, Quote, Booking Order, and payment link. They cannot see policy rule text (only the approval result and next-step recommendation), other employees' data, supplier records, credentials, or audit events. Their own identity documents and payment links are visible only in redacted form.

## Administrator (管理员)

The single management role, whose day job is HR, in the MVP's one organization. It combines Trip Request approval, budget-exception approval, travel administration (manual recovery and policy/supplier-record maintenance), and audit review. Every Administrator is eligible to review an automatically rejected Trip Request after the employee requests review and to decide a specific Booking Order budget exception. All Administrators can see an unclaimed review in a shared queue; the first to claim it owns the review, preventing concurrent handling. Self-review is allowed for now and must be fully recorded in the append-only audit trail; this may be restricted in a later iteration. If nobody claims a review, it remains pending and Administrators are reminded; it is never auto-approved or auto-rejected. Every decision requires a reason, and employees can see the final decision and its reason. The reviewed request version is locked while a Trip Request review is pending; the employee must cancel the review before editing. The Administrator may read all business data and audit events and may mutate policy data, supplier records, and budget thresholds, but may not directly rewrite the append-only content of an employee's Trip Request, Quote, or Booking Order; it acts through approval and recovery workflows. The approver of a Trip Request or budget exception is this role's approval function, not a separate role.

## Audit Event (审计事件)

An append-only, immutable record written by the persistence layer, never by an Agent, for every state transition, Agent task result, HITL decision, supplier response, failure reason, and human takeover. Only the Administrator may read audit events; no role, including the Administrator, may modify or delete them, enforced technically.

## Sensitive Data (敏感数据)

Data that is encrypted at rest and redacted wherever it surfaces: identity documents (ID/passport numbers), contact details, supplier API keys, payment links, payment-status callback payloads, and raw supplier payloads containing PII. Plaintext sensitive data is never visible to any human, including the Administrator; it exists only in encrypted storage and at the moment of the supplier booking call.

## Redaction (脱敏)

The uniform masking applied to sensitive data in every surface (logs, agent context, audit events, admin UI): ID/passport numbers keep the first 3 and last 4 digits, phone numbers keep the first 3 and last 4, email keeps the first character plus the domain, and payment links and API keys are hidden entirely behind opaque references.
