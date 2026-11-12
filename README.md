# YEP — Youth Empowerment Project

Single-file Java OOP web app demonstrating the full YEP spec:
Level 1 & Level 2 Virtual Exchanges, teams, discussions, projects, certificates.

## Live virtual exchange session
The Level One virtual exchange has a dedicated session page with a date countdown, registration/check-in status, registration and attendance totals, and gated room access. Participants register ahead of time; on the scheduled date, registered participants can check in and open the facilitator-provided room link.

The update is additive: it reuses existing classes and styling and does not change model class definitions. Registration and attendance are held in memory, like the rest of this demo application, and reset when the server restarts. A production deployment should persist them and store a precise session start/end time.

## Youth registration and age verification
Youth registrants enter a date of birth instead of choosing an age. The server calculates their age at registration and rejects dates outside the inclusive 18–35 range, future dates, missing dates, and malformed dates. Password confirmation and acceptance of the terms are also checked on the server.

A birth date is still self-reported, so it cannot by itself prevent deliberate misrepresentation. Youth registrations therefore remain unable to sign in until an administrator checks an official proof of age in person and approves the pending record from **Age verification**. YEP does not ask applicants to upload an ID or store document numbers or copies. When approved, the birth date is discarded and the app retains the verification status, reviewer, and time. Rejecting a pending application deletes that pending account so the applicant can register again with corrected information.

The login/registration screens now follow the supplied `Yep.html` navy, blue, and yellow split-card design. No model class fields or definitions were changed; the registration handler and existing `AuthService` validation were updated, and review state is kept in application-level maps. Passwords must be at least eight characters.

The user records, pending birth dates, and review state are in-memory demo data and disappear on restart. A production rollout needs persistent storage, a documented review procedure, restricted administrator access, and retention/deletion rules. Manual review reduces false entries but cannot guarantee that an identity document itself is genuine.

## Requirements
- **JDK 17 or newer** (nothing else!)

## Run
```bash
java YEPWebApp.java
```
