# Test cases

Generated from the test sources, so this list and the tests cannot drift apart.
**309 backend tests across 30 classes, 62 frontend tests across 10 files.**

Regenerate it with `node scripts/generate-test-cases.mjs` after adding or renaming a test.


Backend tests need Docker: the integration tests start a real PostgreSQL 16 through Testcontainers and run
every migration against it, so the schema, the triggers and the views are all exercised rather than mocked.

```bash
mvn test                 # the backend
cd frontend && npm test  # the frontend
```

## Backend

### ApiEdgeCasesTest (integration, 19 tests)

`ApiEdgeCasesTest.java`

The edges of the API that no single feature test owns: what comes back for malformed requests, the response fields the screens rely on, the admin listings, and the gaps found while reviewing the services.

- The Api Reference Is Off By Default
- Broken Json Is A 400 With A Detail
- A Date That Does Not Parse Is A 400
- An Id That Is Not A Number Is A 400 Not A 500
- An Unknown Status Filter Is A 400
- An Unknown Route Is A 404 Problem
- The Wrong Method Is A 405
- No Token A Garbage Token And A Missing Bearer Prefix Are All 401
- A Zero Or Negative Limit Is Treated As One
- A League Says How Many Contests It Has And Whether I Have Paid
- A Stranger Can See A League By Its Invite Code But Not By Its Id
- A Contest Carries Its Winner Lock Time And My Place
- A Withdrawn Contest Request Can Be Made Again
- The Organizer Hears About A Join Request And The Member Hears The Decision
- The Payment Deadline Cannot Be Moved Past The First Contest
- A Stale Version Is Refused So A Tab Cannot Overwrite A Newer Edit
- A Request Nobody Answered Is Closed When The Contest Starts
- Admins See Retired Tournaments And Inactive Teams But Users Cannot List Them
- Removing A Team From A Tournament Takes The Reason As A Query Parameter

### DatabaseSchemaTest (integration, 4 tests)

`DatabaseSchemaTest.java`

Runs every Flyway migration on a real Postgres and lets Hibernate validate all 12 entities against the schema (ddl-auto=validate). If an entity and a table disagree, the context fails to start.

- Migrations Run And Seed Is Loaded
- Contest With Team Outside Its Tournament Is Refused
- Audit Log Is Append Only
- Admin Audit Entry Needs A Reason

### EndToEndJourneyTest (integration, 2 tests)

`EndToEndJourneyTest.java`

The whole thing, start to finish, through the real API.

Every other test checks one rule. This one checks that the rules add up: three friends register, join a league, play two contests, and settle the money. It is here to catch the failures the unit tests cannot see — a calculation that is right in isolation but wrong once a second contest is involved, or money that goes missing between a contest and the settlement.

The last assertion is the one that matters most: every rupee that went in comes back out, apart from the paise a pool could not divide evenly.

- Three Friends Play A League From Sign Up To Settled Up
- A One Off Contest Runs From Join Request To Money Paid Out

### AuditServiceTest (unit, 4 tests)

`audit/AuditServiceTest.java`

- Admin Entry Without Reason Is Refused
- User Entry Without Actor Is Refused
- System Entry Needs No Actor
- Visibility Follows The Action

### AccessGuardTest (unit, 10 tests)

`auth/AccessGuardTest.java`

- Organizer Passes
- Another User Is Refused
- Admin Is Not Enough For Organizer Only Actions
- Organizer Acts As User Without A Reason
- Admin Needs A Reason To Override
- Plain User Cannot Override
- Require Admin With Reason Checks Role And Reason
- Confirmed Member Can See The League
- Pending Member And Stranger Cannot See The League
- Organizer And Admin Can Always See The League

### AuthFlowTest (integration, 11 tests)

`auth/AuthFlowTest.java`

- Register Then Login Then Read Profile
- Duplicate Email Is Refused Ignoring Case
- Short Password Fails Validation
- Wrong Password Is Unauthorized
- Protected Endpoint Needs A Token
- Profile Update Cannot Change The Role
- Invalid Upi Id Is Rejected
- Normal User Cannot Use Admin Endpoints
- Admin Deactivates A User Whose Token Then Stops Working And The Change Is Audited
- Admin Action Without A Reason Is Rejected
- Admin Cannot Change Their Own Access

### AuthServiceTest (unit, 6 tests)

`auth/AuthServiceTest.java`

- Register Lowercases Email Hashes Password And Defaults Display Name
- An Email On The Server Admin List Registers As An Admin
- Register With Existing Email Is A Conflict
- Login Succeeds With Right Password
- Wrong Password And Unknown Email Give The Same Error
- Disabled Account Cannot Log In

### JwtServiceTest (unit, 5 tests)

`auth/JwtServiceTest.java`

- Token Round Trips The User Id
- Expired Token Is Rejected
- Token Signed With Another Secret Is Rejected
- Garbage Token Is Rejected
- Secret Shorter Than 32 Bytes Is Refused At Startup

### PasswordFlowTest (integration, 7 tests)

`auth/PasswordFlowTest.java`

Password changes and resets. There is no email, so a locked-out player asks the admins ("forgot password"), an admin hands out a temporary password, and the player must replace it. Any password change signs out every older session.

- Changing Your Password Needs The Current One
- The New Password Must Differ And Be Long Enough
- A Changed Password Works And Signs Out Older Sessions
- An Admin Reset Gives A Temporary Password That Must Be Replaced
- Only An Admin Can Reset A Password And Only With A Reason
- Forgot Password Always Answers The Same And Tells The Admins Once An Hour
- Forgot Password Needs No Sign In But An Email

### ApiDocsTest (integration, 5 tests)

`common/ApiDocsTest.java`

The API reference: the OpenAPI description springdoc generates from the controllers, and the Scalar page that shows it. Guards that every endpoint is described and grouped, so a new one cannot slip in undocumented.

- The Description Is Served Without Signing In
- Every Endpoint Has A Summary And A Known Group
- Signing In Needs No Token But Everything Else Does
- Only The Api Is Described Not The Docs Page Itself
- The Reference Page Loads Scalar Pointed At The Description

### ContestFlowTest (integration, 26 tests)

`contest/ContestFlowTest.java`

Contests and enrolment over real HTTP against a real Postgres.

- Organizer Creates A League Contest With The Leagues Amount And Tournament
- Only Confirmed Members Are Enrolled When A Contest Is Created
- Contests Can Be Created While The League Is Upcoming Open Or Active
- No New Contests Once The League Has Ended Or Been Cancelled
- League Contest Validation
- Only The League Organizer Can Create League Contests
- Contests Stop At The Planned Total And A Cancelled One Frees Its Slot And Number
- Contest Numbers Can Be Chosen But Not Duplicated Or Out Of Range
- A Member Confirmed Later Is Enrolled In Unstarted Contests Only
- Reversing A Member Takes Them Out Of Unstarted Contests And Confirming Again Brings Them Back
- Anyone Can Create A Standalone Contest And Is Enrolled As Paid
- Standalone Contest Validation
- A Standalone Contest Is Visible To Anyone But Only The Organizer Sees How To Pay
- My Standalone Contests Lists Only Mine
- League Contests Are Visible To Members And Never Show Predictions
- The League Contest List Is In Start Order And For Members Only
- Organizer Moves The Start Time And Members Can See The History
- Start Time Change Rules
- The Start Time Cannot Change Once The Contest Has Started
- Only The Organizer Or An Admin With A Reason Can Change The Start Time
- A Standalone Start Time Cannot Move Before Its Payment Deadline
- Organizer Cancels A Contest And Members Can See It
- Cancel Permissions
- A Started Contest Cannot Be Cancelled
- Cancelling A Standalone Contest Refunds Whoever Had Paid
- A Standalone Contest With No Paying Participant Can Be Cancelled

### ContestMembershipFlowTest (integration, 13 tests)

`contest/ContestMembershipFlowTest.java`

Joining somebody else's standalone contest, and the organizer's side of it.

- A Request Is Accepted As Unpaid And Then Confirmed When The Money Arrives
- Accepting Someone Who Has Already Paid Confirms Them In One Step
- Only The Organizer Can Accept A Request
- Asking Twice Is Refused But Someone Rejected May Ask Again
- A Pending Request Can Be Withdrawn But A Confirmed Place Cannot
- Joining Is Refused Once The Registration Deadline Has Passed
- Joining Is Refused Once The Contest Has Started
- A League Contest Cannot Be Joined Directly
- The Organizer Sees Payment Details And Other Players Do Not
- A Confirmed Place Can Be Reversed Before The Contest Starts
- A Place Cannot Be Reversed Once The Contest Has Started
- The Organizers Own Place Cannot Be Touched
- The Organizer Is Told When Someone Asks To Join

### PredictionFlowTest (integration, 8 tests)

`contest/PredictionFlowTest.java`

Picking a winner: who may pick, until when, and when other people's picks become visible.

- A Confirmed Member Can Pick A Team And Change Their Mind
- A Team That Is Not Playing Is Rejected
- A Pick Is Refused Once The Start Time Has Passed
- Someone Who Is Not A Participant Cannot Pick
- A Member Who Is Not Confirmed Cannot Pick
- Other Peoples Picks Are Hidden Until The Contest Starts
- Every Pick Is Visible Once The Contest Starts
- The Database Trigger Blocks A Late Pick Even If The Service Misses It

### ResultFlowTest (integration, 12 tests)

`contest/ResultFlowTest.java`

Declaring a result: the preview, the two-step confirm, and corrections.

- The Preview Shows The Pool And Each Winners Share Without Saving Anything
- Confirming Writes Every Participants Result And Money
- Confirming Without A Preview Token Is Refused
- A Stale Token Is Refused So Nobody Settles Figures They Never Saw
- A Result Cannot Be Entered Before The Contest Locks
- Only The Organizer Or An Admin Can Declare A Result
- A No Result Contest Refunds Everyone And Spends Nothing
- Sending Both A Winner And No Result Is Rejected
- The Same Result Cannot Be Confirmed Twice
- A Correction Needs A Reason And Rewrites The Money
- The Outcomes Endpoint Shows Who Won What
- Everyone Is Told Once The Result Is In

### DashboardFlowTest (integration, 10 tests)

`dashboard/DashboardFlowTest.java`

The read-only screens: the league table, a player's record, and the home screen.

- The League Board Ranks Members By Profit And Loss
- The Board Marks The Callers Own Line
- Only Members Can See A Leagues Board
- The Board Shows Settlement Status Once The League Has Ended
- My Stats Count Wins Losses And The Win Percentage
- A Brand New Player Has An Empty Record Rather Than An Error
- A Refunded Contest Is Not Counted As Played
- The Home Screen Shows Money Still Owed To The Caller
- The Home Screen Tells An Organizer Which Results Are Waiting On Them
- The Home Screen Shows What Comes Back To The Caller After A League Ends

### PeriodStatsTest (unit, 5 tests)

`dashboard/PeriodStatsTest.java`

The profile's record for a period, worked out without a database.

- Nothing Played Is All Zeroes
- Money In Winnings And Profit Add Up
- Streaks Count Runs In Start Time Order
- Months Follow India Time
- A Range Starts That Many Months Back And All Has No Start

### StatsAndExportFlowTest (integration, 4 tests)

`export/StatsAndExportFlowTest.java`

The profile's stats by period, and the Excel copies of a season and of a player's own history.

- Stats For A Period Count Only Contests That Started In It
- An Unknown Range Is A Bad Request
- My Export Holds My Contests And The Ones I Organise
- A League Export Is For Members And Hides Picks Before The Start

### ContestStartJobTest (integration, 10 tests)

`jobs/ContestStartJobTest.java`

What the clock does on its own: locking a contest at its start time, starting a league with its first contest, and cancelling what cannot go ahead.

- A Contest With Enough Players Locks At Its Start Time
- Locking A Contest Starts Its League
- A Request Still Waiting When The League Starts Is Withdrawn
- A League With Too Few Paid Members Is Cancelled Instead Of Starting
- A Standalone Contest With Too Few Players Is Cancelled And Entries Refunded
- An Unpaid Place Is Cut Off Before The Contest Counts Its Players
- The Job Ignores A Contest Whose Start Time Has Not Arrived
- Running The Job Twice Changes Nothing The Second Time
- Everybody Is Told That Predictions Are Closed
- Locking A Contest Drops Its Queued Reminders

### DevToolsTest (integration, 3 tests)

`jobs/DevToolsTest.java`

The admin-only shortcuts for trying the app out. They were once broken by self-invocation: startNow called fastForward on itself, skipping the transaction proxy, so the lock button failed every time.

- Start Now Locks A League Contest And Starts Its League
- Start Now Locks A Standalone Contest Whose Deadlines Were Still Ahead
- The Shortcuts Are Admin Only

### InviteCodesTest (unit, 2 tests)

`league/InviteCodesTest.java`

- Codes Have The Right Length And Only Unambiguous Characters
- A Thousand Codes Are All Different

### LeagueFlowTest (integration, 30 tests)

`league/LeagueFlowTest.java`

Leagues over real HTTP against a real Postgres. Uses the seeded IPL 2026 tournament (read only).

- Creator Becomes Organizer And A Confirmed Paid Member
- League Whose Registration Start Has Passed Opens Immediately
- Invalid Schedule Is Refused
- Invalid Amounts And Counts Fail Validation
- Unknown Or Inactive Tournament Is Not Found
- Creating Needs A Login
- Organizer Sees Invite Code And Payment Instructions But A Stranger Cannot Open The League
- Invite Link Shows The Join Screen Without Secrets
- An Accepted Member Sees Payment Instructions But Not The Invite Code
- Admin Can See Any League With Its Invite Code
- My Leagues Lists Organized And Pending But Not Rejected
- Organizer Edits The League And The Change Is Audited
- Edit With An Old Version Is Refused As Stale
- Only The Organizer Can Edit
- Amount And Contest Count Are Locked Once A Member Is Accepted But Other Fields Stay Editable
- Organizer Row Does Not Lock The Amount
- Amount And Contest Count Are Locked Once A Contest Exists
- A League That Has Started Cannot Be Edited
- Registration Start Cannot Change Once Joining Is Open
- Edit With A Bad Schedule Is Refused
- Payment Deadline Can Be Extended After The Registration Deadline Has Passed
- Organizer Opens Joining Early
- Joining Cannot Be Opened After The Registration Deadline
- The Open Job Opens Due Leagues Once
- Organizer Cancels An Unstarted League
- Organizer Can Cancel Without A Reason
- Cancelling A League Members Have Paid For Leaves Money To Return
- Cancel With Only Unpaid Members Is Allowed
- Admin Cancel Needs A Reason And Is Audited As Admin
- Cancelling A League Cancels Its Scheduled Contests

### LeagueMembershipFlowTest (integration, 27 tests)

`league/LeagueMembershipFlowTest.java`

The join flow over real HTTP: request, withdraw, accept, confirm payment, reject, remove, reverse.

- Cannot Request Before Joining Opens
- User Requests To Join An Open League
- Duplicate Request And Organizer Request Are Refused
- Cannot Request After The Registration Deadline
- Cannot Request Once The League Has Started Or Been Cancelled
- Requesting Needs A Login
- Accept As Paid Confirms The Member And Publishes An Event
- Accept Unpaid Then Confirm Payment
- Confirm Payment Before Accepting Is Refused
- Only The Organizer Can Act On Members
- Admin Acting For The Organizer Needs A Reason And Is Audited As Admin
- An Action On A Member Of Another League Is Not Found
- The Organizers Own Membership Cannot Be Changed
- Accepting Is Refused Once The League Has Started
- Payment Deadline Rules For Accepting And Confirming
- Reject Keeps The Reason And The User Can Ask Again
- A Confirmed Member Cannot Be Rejected
- Remove An Unpaid Member Needs A Reason And Is Final
- A Confirmed Member Must Be Reversed Not Removed
- A Pending Or Accepted User Can Withdraw And Ask Again
- A Confirmed Member Cannot Withdraw
- Withdrawing Without A Request Is Not Found
- Reverse Before Start To Unpaid Returns The Money And Publishes A Loss Event
- Reverse Before Start To Removed Resets Payment So No Settlement Row Will Exist
- Reverse After Start Is Admin Only And Keeps The Payment
- Reverse Is Refused When The League Is Not Open Or Active
- Member List Visibility And Privacy

### NotificationFlowTest (integration, 8 tests)

`notification/NotificationFlowTest.java`

The in-app inbox: what lands in it, when a queued reminder is delivered, and reading it.

- A New Contest Queues Reminders For Every Member
- A Queued Reminder Stays Out Of The Inbox Until It Is Due
- Moving A Contest Throws Away The Old Reminders And Queues New Ones
- Cancelling A Contest Drops Its Reminders And Says So
- The Unread Count Drops As Things Are Read
- An Inbox Is Private To Its Owner
- The Same Notification Is Not Delivered Twice
- The Sender Skips Nothing And Reports What It Sent

### ParticipationRulesTest (unit, 4 tests)

`participation/ParticipationRulesTest.java`

- Allowed Moves
- Refused Moves
- Moving To The Same Status Is Refused
- Unknown Status Is Refused

### ParticipationServiceTest (unit, 21 tests)

`participation/ParticipationServiceTest.java`

- Accept Paid Confirms Marks Paid And Publishes A Gain Event
- Accept Unpaid Does Not Confirm And Publishes No Event
- Confirm Payment Moves Accepted Unpaid To Confirmed
- Confirm Payment Before Accepting Is Refused
- Accepting Only Works For A Pending Request
- Reversing Only Works For A Confirmed Participation
- Reject Keeps The Reason
- Remove Works For Accepted Unpaid But Not For Confirmed
- Remove Needs A Reason
- Reverse To Unpaid Resets Payment And Confirmation And Publishes A Loss Event
- Reverse To Removed Before Start Returns The Money
- Reverse To Removed After Start Keeps The Payment For Settlement
- Reverse To Unpaid Cannot Keep The Payment
- Reverse Needs A Reason
- A Confirmed User Cannot Withdraw
- A Pending Or Accepted User Can Withdraw
- Request Again Works After Rejection Or Withdrawal But Not After Removal
- Cutoff Removes As System Without An Actor
- Cutoff Refuses Anything But Accepted Unpaid
- Admin Action Passes Its Reason To The Audit Entry
- Contest Participants Are Audited With The Contest Id

### PayoutCalculatorTest (unit, 10 tests)

`payout/PayoutCalculatorTest.java`

The money rules, with no database in the way.

- The Winners Split The Pool Equally
- An Uneven Split Rounds Down And Keeps The Remainder As Leftover
- A Participant Who Never Predicted Loses But Still Feeds The Pool
- Nobody Picking The Winner Refunds Everyone
- Everybody Picking The Winner Refunds Everyone
- A No Result Contest Refunds Everyone
- A Single Winner Takes The Whole Pool
- A Winner Always Gets More Than Zero So The Database Check Holds
- No Confirmed Participants Means Nothing To Settle
- An Amount That Is Not Positive Is Rejected

### PayoutFlowTest (integration, 13 tests)

`payout/PayoutFlowTest.java`

The money side of a standalone contest: who is owed what, and ticking it off once it has been sent.

- A Winning Payout Is Created For The Winner And Paid Straight Away For The Organizer
- The Organizers Own Winnings Need No Transfer
- The Contest Becomes Settled Once Every Payout Is Marked Paid
- Taking A Payout Back Needs A Reason And Reopens The Contest
- A Participant Sees Only Their Own Payout
- Only The Organizer Can Mark A Payout As Paid
- A Payout Cannot Be Voided Through The Api
- A No Result Contest Refunds Every Entry
- Correcting A Result Is Refused Once Somebody Else Has Been Paid
- Correcting A Result Replaces The Payouts That Have Not Been Paid
- Correcting A Result Voids The Organizers Own Automatic Payout When They No Longer Win
- Correcting A Result To No Result Refunds The Organizer Too
- A League Contest Has No Payouts At All

### SettlementFlowTest (integration, 15 tests)

`settlement/SettlementFlowTest.java`

Ending a league, the closing balances, and settling up.

- Ending A League Freezes Everybodys Balance And The Figures Add Up
- The Organizers Own Row Is Settled Straight Away And Every Member Is Waiting For Their Money
- A League Cannot Be Ended While A Contest Still Owes A Result
- A League Cannot Be Ended While A Contest Has Started But Not Locked Yet
- Ending A League Cancels Contests That Were Never Played
- Only The Organizer Or An Admin Can End A League
- The Summary Lists Every Member The Organizer Has To Pay Back
- A League Cannot Be Completed While Somebody Has Not Settled
- A League Is Completed Once Everybody Is Ticked Off
- Taking A Settlement Back Needs A Reason
- An Admin Can Force Complete A Stuck League And What Is Left Is Written Off
- Correcting A Result After The League Ended Is Admin Only And Redoes The Pending Balances
- A Correction Is Refused Once Somebody Has Settled
- A Member With Nothing Left Does Not Block A Correction And Is Redone
- Everybody Is Told Where They Stand When The League Ends

### TournamentFlowTest (integration, 13 tests)

`tournament/TournamentFlowTest.java`

Uses its own tournaments and teams, so it never changes the seeded IPL 2026 data other tests rely on.

- Seeded Ipl Tournament Lists Its Ten Teams
- Pickers Need A Login
- Normal User Cannot Create Teams Or Tournaments
- Admin Builds A Tournament With Teams
- Short Code Is Stored Upper Case And Must Be Unique
- Invalid Short Code Fails Validation
- End Date Before Start Date Is Refused
- Deactivated Team Leaves The Picker And The Change Is Audited
- Inactive Team Cannot Be Added To A Tournament
- Team Used By A Contest Cannot Be Removed From The Tournament
- Unused Team Can Be Removed And The Removal Is Audited
- Removing A Team That Is Not In The Tournament Is Not Found
- Unknown Tournament Is Not Found

### AdminBootstrapTest (unit, 2 tests)

`user/AdminBootstrapTest.java`

The first admins come from server configuration (app.admin-emails), never from a request.

- The List Is Trimmed And Case Insensitive
- Listed Accounts Are Made Admins Once And Audited

## Frontend

### client.test.ts (9 tests)

`src/api/client.test.ts`

- Sends the stored token as a bearer header
- Leaves the token off a sign-in request, where there is not one yet
- Turns the backend problem document into a readable message
- Clears the token and announces a 401, so the whole app signs out
- Does not sign the user out when a sign-in attempt is refused
- Reports an unreachable backend as something a person can act on
- Falls back to a sensible message when the body is not a problem document
- Handles a 204 with no body
- Prefers the UTF-8 name the backend sends, and falls back to the plain one

### ChangePasswordForm.test.tsx (2 tests)

`src/components/ChangePasswordForm.test.tsx`

- Does not send two new passwords that differ
- Swaps in the new token the backend returns

### format.test.ts (10 tests)

`src/lib/format.test.ts`

- Shows a decimal string from the backend to the paisa
- Groups in the Indian style
- Shows a dash rather than zero for nothing at all
- Shows a real zero as zero
- Passes anything unparseable through rather than printing NaN
- Marks the direction, because a profit and a loss read the same otherwise
- Classifies a profit, a loss and breaking even
- Turns a backend enum into something readable
- Survives going to an input value and back to an instant
- Knows whether a deadline has gone

### tableLabels.test.ts (1 test)

`src/lib/tableLabels.test.ts`

- Copies each heading onto its cells, leaving a cell that spans columns unlabelled

### JoinLeaguePage.test.tsx (6 tests)

`src/pages/JoinLeaguePage.test.tsx`

- Shows what the league costs and asks for a place
- Looks the code up in capitals whatever was typed
- Sends somebody who already has a place straight to the league
- Lets somebody who was turned down ask again
- Says joining is closed instead of offering a button that would be refused
- Explains an unknown code

### SignInPage.test.tsx (6 tests)

`src/pages/SignInPage.test.tsx`

- Stores the token that comes back from a successful sign-in
- Shows the reason a sign-in was refused
- Asks for a name when registering, and signs the new user straight in
- Leaves the display name out when it was not filled in, so the backend uses the name
- Opens on the register form, saying where it leads, for somebody following a contest invite
- Asks the admins for a reset and answers the same whatever the email

### PredictionPanel.test.tsx (7 tests)

`src/pages/contest/PredictionPanel.test.tsx`

- Offers both teams and sends the one that is clicked
- Warns a player who has not picked that their entry still counts
- Marks the pick that was made and says it can still be changed
- Disables both teams once the contest has locked
- Keeps other people’s picks hidden while the contest is still to start
- Shows everyone once it has started, including whoever never picked
- Says so plainly when the user is not in the contest at all

### ResultPanel.test.tsx (7 tests)

`src/pages/contest/ResultPanel.test.tsx`

- Will not offer to confirm until a preview has been seen
- Shows the pool and each winner’s share before anything is saved
- Sends the preview token back with the confirmation
- Explains a refund-all outcome rather than just showing zeroes
- Offers a no-result option for an abandoned match
- Will not let a correction go without a reason
- Shows no declare panel to somebody who does not organize the contest

### BoardTab.test.tsx (6 tests)

`src/pages/league/BoardTab.test.tsx`

- Lists every player with their profit and their balance
- Marks the caller’s own line
- Shows how far through the league is
- Explains an empty table rather than showing nothing
- Mentions the paise a pool could not divide, when there are any
- Shows settlement status once the league has ended

### SettlementTab.test.tsx (8 tests)

`src/pages/league/SettlementTab.test.tsx`

- Lists the whole final balance each player gets back, the loser included
- Says the figures add up when they do
- Refuses to let the league close while the figures do not add up
- Refuses to let the league close while somebody has not settled
- Lets the league close once everybody is ticked off
- Asks for a reason before undoing a settlement
- Marks somebody settled with the amount spelled out
- Says nothing is settled yet while the league is still being played
