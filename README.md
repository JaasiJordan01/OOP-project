# YEP — Youth Empowerment Project

Single-file Java OOP web app demonstrating the full YEP spec:
Level 1 & Level 2 Virtual Exchanges, teams, discussions, projects, certificates.

## Responsive navigation
The main application navigation collapses to a touch-friendly hamburger menu on phone-sized screens and opens as a slide-in drawer with a backdrop. The drawer supports Escape-to-close, focus handling, and responsive layouts for cards, forms, and the existing split-card sign-in/registration screens.

## Live virtual exchange session
The Level One virtual exchange has a dedicated session page with a date countdown, registration/check-in status, registration and attendance totals, and gated room access. Participants register ahead of time; on the scheduled date, registered participants can check in and open the facilitator-provided room link.

The update is additive: it reuses existing classes and styling without changing existing model fields. Registration and attendance are held in memory, like the rest of this demo application, and reset when the server restarts. A production deployment should persist them and store a precise session start/end time.

## Youth registration and age verification
Youth registrants enter a date of birth instead of choosing an age. The server calculates their age at registration and rejects dates outside the inclusive 18–35 range, future dates, missing dates, and malformed dates. Password confirmation and acceptance of the terms are also checked on the server.

A birth date is still self-reported, so it cannot by itself prevent deliberate misrepresentation. A youth account remains restricted until an administrator checks an official proof of age in person and approves the pending record from **Age verification**. The restricted signup session permits Level One enrollment only; it does not permit general learning, collaboration, or Level Two access. YEP does not ask applicants to upload an ID or store document numbers or copies. When approved, the birth date is discarded and the app retains the verification status, reviewer, and time. Rejecting a pending application deletes that pending account so the applicant can register again with corrected information.

The login/registration screens now follow the supplied `Yep.html` navy, blue, and yellow split-card design. Existing entity fields were not expanded; the registration handler, `UserRole`, `UserFactory`, and `AuthService` were updated, and review state is kept in application-level maps. Passwords must be at least eight characters.

The user records, pending birth dates, and review state are in-memory demo data and disappear on restart. A production rollout needs persistent storage, a documented review procedure, restricted administrator access, and retention/deletion rules. Manual review reduces false entries but cannot guarantee that an identity document itself is genuine.

## Stakeholders, enrollment, and profiles
Registration supports Youth Participants, Facilitators/Lecturers, Mentors, Universities/Educational Institutions, Employers, Partner Organisations, and Researchers/Students. Administrator accounts are provisioned by the system rather than through public registration. Employer, institution, partner, and researcher/student accounts are limited to their own profile and public activity information; they do not get unrestricted access to participant, team, or project records.

After youth signup, the participant is signed in to a restricted enrollment session and can enroll in Level One activities immediately. While age review is pending, only enrollment, profile/photo management, and logout are available. An administrator's approval unlocks the rest of the learning platform; rejection removes the pending account and its enrollment/photo data.

Level One progress is calculated from completed enrollments in every published Level One activity. Youth participants can access Level Two exchanges, teams, and project pages only after all of those activities are complete; the restriction is enforced on page views and form submissions. Seed demonstration participants are marked complete across the Level One path so the existing Level Two demo remains usable. In this prototype, the activity completion button records completion and is not a substitute for a production assessment or attendance-verification workflow.

All stakeholder accounts can add or update a profile photo from **My Profile**. Uploads are limited to PNG/JPEG, 2 MB, and bounded image dimensions; filenames are randomized and the image signature is checked. Files are stored locally in `uploads/profile-photos/` by default (or in the directory configured by `YEP_PROFILE_PHOTO_DIR`) and are excluded from Git. User/profile records remain in memory and reset on restart, so local photo files can outlive their corresponding demo records.

The original model fields were not expanded. The existing `Profile.photoUrl` is reused; `UserRole` gained the additional stakeholder types and a `StakeholderAccount` class represents institution, employer, and researcher/student accounts. `UserFactory` and `AuthService` were updated to create and initialize these stakeholder accounts.

## Requirements
- **JDK 17 or newer** (nothing else!)

## Run
```bash
java YEPWebApp.java
```
