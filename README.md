# YEP — Youth Empowerment Project

Single-file Java OOP web app demonstrating the full YEP spec:
Level 1 & Level 2 Virtual Exchanges, teams, discussions, projects, certificates.

## Live virtual exchange session
The Level One virtual exchange has a dedicated session page with a date countdown, registration/check-in status, registration and attendance totals, and gated room access. Participants register ahead of time; on the scheduled date, registered participants can check in and open the facilitator-provided room link.

The update is additive: it reuses existing classes and styling and does not change model class definitions. Registration and attendance are held in memory, like the rest of this demo application, and reset when the server restarts. A production deployment should persist them and store a precise session start/end time.

## Requirements
- **JDK 17 or newer** (nothing else!)

## Run
```bash
java YEPWebApp.java
```
