import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * =============================================================================
 * YEP — Youth Empowerment Project
 * Single-file Java web app (OOP core + embedded HTTP server + HTML UI)
 * =============================================================================
 * Run locally: java YEPWebApp.java
 * Open:        http://localhost:8080/
 *
 * Render Ready: uses System.getenv("PORT") for dynamic deployment.
 * =============================================================================
 */
public class YEPWebApp {

    // ==================== GLOBAL STATE ====================
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    static final Repository<User>             USERS      = new InMemoryRepository<>();
    static final Repository<LearningActivity> ACTIVITIES = new InMemoryRepository<>();
    static final Repository<Enrollment>       ENROLLS    = new InMemoryRepository<>();
    static final Repository<DiscussionThread> THREADS    = new InMemoryRepository<>();
    static final Repository<DiscussionPost>   POSTS      = new InMemoryRepository<>();
    static final Repository<Team>             TEAMS      = new InMemoryRepository<>();
    static final Repository<ImpactProject>    PROJECTS   = new InMemoryRepository<>();
    static final Repository<Certificate>      CERTS      = new InMemoryRepository<>();

    static final Map<String, User> SESSIONS = new ConcurrentHashMap<>();
    static final Map<String, String> FLASH_MESSAGES = new ConcurrentHashMap<>();
    static final Map<Long, Set<Long>> LIVE_ATTENDANCE = new ConcurrentHashMap<>();
    static final Map<Long, String> YOUTH_AGE_STATUS = new ConcurrentHashMap<>();
    static final Map<Long, LocalDate> YOUTH_DATE_OF_BIRTH = new ConcurrentHashMap<>();
    static final Map<Long, LocalDate> YOUTH_REGISTERED_ON = new ConcurrentHashMap<>();
    static final Map<Long, Long> AGE_REVIEWED_BY = new ConcurrentHashMap<>();
    static final Map<Long, LocalDateTime> AGE_REVIEWED_AT = new ConcurrentHashMap<>();

    // ==================== SERVICES ====================
    static final AuthService        AUTH     = new AuthService(USERS);
    static final LearningService    LEARNING = new LearningService(ACTIVITIES, ENROLLS);
    static final DiscussionService  DISCUSS  = new DiscussionService(THREADS, POSTS);
    static final TeamService        TEAM_SVC = new TeamService(TEAMS, USERS);
    static final ProjectService     PROJ_SVC = new ProjectService(PROJECTS);
    static final AssessmentService  ASSESS   = new AssessmentService(CERTS);
    static final NotificationService NOTIFY  = new NotificationService();

    // ==================== MAIN ====================

    public static void main(String[] args) throws Exception {
        seedData();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", YEPWebApp::handle);
        server.setExecutor(Executors.newFixedThreadPool(20));
        server.start();

        System.out.println("=".repeat(64));
        System.out.println("  YEP — Youth Empowerment Project");
        System.out.println("  Server running at http://localhost:" + PORT + "/");
        System.out.println("=".repeat(64));
        System.out.println("  Demo logins (password = 'password'):");
        System.out.println("    alice@yep.ug   (Youth participant)");
        System.out.println("    bob@yep.ug     (Youth participant)");
        System.out.println("    grace@yep.ug   (Mentor)");
        System.out.println("    david@yep.ug   (Facilitator)");
        System.out.println("    admin@yep.ug   (Administrator)");
        System.out.println("=".repeat(64));
    }

    // ==================== ROUTING ====================

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path   = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            User   user   = currentUser(ex);

            switch (path) {
                case "/"            -> home(ex, user);
                case "/register"    -> { if (method.equals("POST")) registerPost(ex); else registerGet(ex, null); }
                case "/login"       -> { if (method.equals("POST")) loginPost(ex);    else loginGet(ex, null); }
                case "/logout"      -> logout(ex);
                case "/age-verification" -> {
                    if (method.equals("GET")) ageVerificationGet(ex, user);
                    else sendHtml(ex, 405, page("Method not allowed", user, "/age-verification",
                            "<div class='card'><h1>Method not allowed</h1></div>"));
                }
                case "/age-verification/review" -> {
                    if (method.equals("POST")) ageVerificationReviewPost(ex, user);
                    else sendHtml(ex, 405, page("Method not allowed", user, "/age-verification",
                            "<div class='card'><h1>Method not allowed</h1></div>"));
                }
                case "/dashboard"   -> dashboard(ex, user);
                case "/activities"  -> activities(ex, user);
                case "/enroll"      -> enroll(ex, user);
                case "/level-one"   -> levelOne(ex, user);
                case "/level-two"   -> levelTwo(ex, user);
                case "/team"        -> { if (method.equals("POST")) teamPost(ex, user); else redirect(ex, "/level-two"); }
                case "/discussion"  -> { if (method.equals("POST")) discussionPost(ex, user); else discussionGet(ex, user); }
                case "/thread"      -> { if (method.equals("POST")) threadPost(ex, user); else threadGet(ex, user); }
                case "/live-session" -> { if (method.equals("POST")) liveSessionPost(ex, user); else liveSessionGet(ex, user); }
                case "/live-session/check-in" -> {
                    if (method.equals("POST")) liveSessionCheckIn(ex, user);
                    else sendHtml(ex, 405, page("Method not allowed", user, "/live-session",
                            "<div class='card'><h1>Method not allowed</h1></div>"));
                }
                case "/certificate" -> certificate(ex, user);
                default             -> sendHtml(ex, 404, page("Not found", user, "/",
                        "<div class='card text-center'><h1>404</h1><p class='muted'>Page not found.</p><a class='btn primary' href='/'>Return Home</a></div>"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendHtml(ex, 500, page("Error", null, "/",
                        "<div class='card'><h1>Oops! Something went wrong.</h1><pre class='muted'>" + esc(e.toString()) + "</pre></div>"));
            } catch (Exception ignored) {}
        }
    }

    // ==================== PAGE HANDLERS ====================

    static void home(HttpExchange ex, User user) throws IOException {
        String content =
            carousel() +
            "<section class='grid mt-4'>" +
                "<div class='card text-center'><h2 style='font-size:2rem'>🎓</h2><h3>Digital Learning</h3><p class='muted'>AI literacy, media literacy, and critical thinking — free and mobile-first.</p></div>" +
                "<div class='card text-center'><h2 style='font-size:2rem'>🌍</h2><h3>Virtual Exchange</h3><p class='muted'>Level One large-group sessions and Level Two team projects with peers across East Africa.</p></div>" +
                "<div class='card text-center'><h2 style='font-size:2rem'>💬</h2><h3>Discussions</h3><p class='muted'>Ask questions, share ideas, and get guidance from mentors and facilitators.</p></div>" +
                "<div class='card text-center'><h2 style='font-size:2rem'>🏅</h2><h3>Certificates</h3><p class='muted'>Verifiable digital certificates that universities and employers can trust.</p></div>" +
            "</section>" +
            "<div class='card text-center mt-4' style='background:linear-gradient(135deg,#0a1f5c,#1d4ed8);color:#fff;border:none'>" +
                "<h2 style='color:#fff'>Ready to join the exchange?</h2>" +
                "<p style='opacity:0.9;margin-bottom:20px'>Learn together. Build together. Get recognised.</p>" +
                "<a class='btn' style='background:#fbbf24;color:#0a1f5c;border:none;margin-right:10px' href='/register'>Create account</a> " +
                "<a class='btn' style='background:transparent;color:#fff;border:1px solid #fff' href='/login'>Sign in</a>" +
            "</div>";
        sendHtml(ex, 200, page("Welcome", user, "/", content));
    }

    static void registerGet(HttpExchange ex, String error) throws IOException {
        LocalDate today = LocalDate.now();
        String latestDob = today.minusYears(18).toString();
        String earliestDob = today.minusYears(36).plusDays(1).toString();
        String errorHtml = error == null ? "" : "<p class='err'>" + esc(error) + "</p>";
        String content = """
            <form class="form" method="post" action="/register">
              <h1>Create Account</h1>
              <p class="sub">Start your journey with YEP</p>
              %s
              <label class="fld"><span aria-hidden="true">👤</span><input name="name" required placeholder="Full Name" autocomplete="name"></label>
              <label class="fld"><span aria-hidden="true">✉️</span><input type="email" name="email" required placeholder="Email Address" autocomplete="email"></label>
              <label class="fld"><span aria-hidden="true">📞</span><input type="tel" name="phone" placeholder="Phone Number" autocomplete="tel"></label>
              <label class="fld"><span aria-hidden="true">🎓</span><select name="role" id="role" aria-label="Account type" onchange="toggleRoleFields()" required>
                <option value="YOUTH">Youth Participant (18–35)</option>
                <option value="MENTOR">Mentor</option>
                <option value="FACILITATOR">Facilitator</option>
              </select></label>
              <div id="youthFields" class="auth-fields">
                <label class="fld"><span aria-hidden="true">🎂</span><input type="date" id="dob" name="dob" min="%s" max="%s" required aria-label="Date of birth"></label>
                <p class="hint">Enter your real date of birth. YEP calculates your age on the server. Youth accounts stay pending until an administrator checks proof of age.</p>
                <div class="auth-grid">
                  <label class="fld"><span aria-hidden="true">⚧</span><select name="gender" aria-label="Gender"><option value="">Gender (optional)</option><option>Female</option><option>Male</option><option>Other</option></select></label>
                  <label class="fld"><span aria-hidden="true">🌍</span><select name="country" aria-label="Country"><option value="">Country</option>%s</select></label>
                </div>
                <label class="fld"><span aria-hidden="true">📍</span><input name="district" placeholder="District" autocomplete="address-level2"></label>
                <label class="fld"><span aria-hidden="true">📚</span><select name="education" aria-label="Highest education"><option value="">Highest education (optional)</option>%s</select></label>
              </div>
              <div id="mentorFields" class="auth-fields" style="display:none">
                <label class="fld"><span aria-hidden="true">✨</span><select name="expertise" aria-label="Area of expertise"><option value="">Area of expertise</option>%s</select></label>
                <label class="fld"><span aria-hidden="true">🏢</span><input name="organisation" placeholder="Organisation"></label>
              </div>
              <label class="fld"><span aria-hidden="true">🔒</span><input type="password" name="password" required minlength="8" placeholder="Password (at least 8 characters)" autocomplete="new-password"></label>
              <label class="fld"><span aria-hidden="true">🔒</span><input type="password" name="confirmPassword" required minlength="8" placeholder="Confirm Password" autocomplete="new-password"></label>
              <label class="terms"><input type="checkbox" name="terms" value="accepted" required><span>I agree to the Terms &amp; Conditions and Privacy Policy.</span></label>
              <button class="btn primary full" type="submit">Create Account →</button>
              <div class="ctr">Already have an account? <a href="/login">Sign In</a></div>
              <div class="copy">© 2026 YEP. All rights reserved.</div>
            </form>
            <script>
              function toggleRoleFields(){
                var youth=document.getElementById('role').value==='YOUTH';
                document.getElementById('youthFields').style.display=youth?'grid':'none';
                document.getElementById('mentorFields').style.display=youth?'none':'grid';
                document.getElementById('dob').required=youth;
              }
              toggleRoleFields();
            </script>
            """.formatted(errorHtml, earliestDob, latestDob,
                dropdownOptions(new String[]{"Uganda","Kenya","Tanzania","Rwanda","Burundi","South Sudan","Ethiopia","Somalia"}),
                dropdownOptions(new String[]{"Secondary","Certificate","Diploma","Bachelor's Degree","Master's Degree","Other"}),
                dropdownOptions(new String[]{"Digital Literacy","AI Ethics","AgriTech","HealthTech","Climate & Sustainability","Entrepreneurship","Education","Media & Journalism"}));
        sendHtml(ex, 200, authPage("Create Account", "Together<br>We Grow<br><b>Stronger</b>",
                "Join the YEP community and unlock opportunities for learning, skills and impact.", content));
    }

    static void registerPost(HttpExchange ex) throws IOException {
        Map<String, String> f = parseForm(ex);
        try {
            String role = f.get("role");
            if (role == null || !List.of("YOUTH", "MENTOR", "FACILITATOR").contains(role)) {
                throw new ValidationException("Choose a supported registration type.");
            }
            if (!"accepted".equals(f.get("terms"))) throw new ValidationException("Please accept the Terms & Conditions and Privacy Policy.");
            if (!Objects.equals(f.get("password"), f.get("confirmPassword"))) throw new ValidationException("Passwords do not match.");
            LocalDate dob = null;
            Integer age = null;
            if ("YOUTH".equalsIgnoreCase(f.get("role"))) {
                dob = parseDateOfBirth(f.get("dob"));
                age = ageOn(dob, LocalDate.now());
            }
            User u = AUTH.register(
                f.get("role"), f.get("name"), f.get("email"), f.get("phone"), f.get("password"),
                age, f.get("gender"), f.get("country"),
                f.get("district"), f.get("education"),
                f.get("expertise"), f.get("organisation")
            );
            if (u instanceof YouthParticipant) {
                u.suspend();
                YOUTH_AGE_STATUS.put(u.getId(), "PENDING");
                YOUTH_DATE_OF_BIRTH.put(u.getId(), dob);
                YOUTH_REGISTERED_ON.put(u.getId(), LocalDate.now());
                String pending = "<div class='auth-message'><div class='auth-symbol'>✓</div><h1>Registration received</h1>"
                        + "<p>Your age is within the programme range. An administrator must review proof of age before you can sign in.</p>"
                        + "<p>For your privacy, YEP does not ask you to upload an identity document. Please present an official proof of age to the YEP team.</p>"
                        + "<a class='btn primary full' href='/login'>Continue to sign in</a></div>";
                sendHtml(ex, 200, authPage("Registration received", "Your YEP journey<br><b>starts here</b>",
                        "A quick age check helps keep the youth programme safe and fair.", pending));
                return;
            }
            String sid = UUID.randomUUID().toString();
            SESSIONS.put(sid, u);
            setSessionCookie(ex, sid);
            setFlash(ex, "Account created successfully. Welcome to YEP, " + u.getName() + "!");
            redirect(ex, "/dashboard");
        } catch (Exception e) {
            registerGet(ex, e.getMessage());
        }
    }

    static void loginGet(HttpExchange ex, String error) throws IOException {
        String errorHtml = error == null ? "" : "<p class='err'>" + esc(error) + "</p>";
        String content = """
            <form class="form" method="post" action="/login">
              <h1>Welcome Back</h1>
              <p class="sub">Sign in to your account to continue</p>
              %s
              <label class="fld"><span aria-hidden="true">✉️</span><input type="email" name="email" required placeholder="Email Address" autocomplete="email"></label>
              <label class="fld"><span aria-hidden="true">🔒</span><input type="password" name="password" required placeholder="Password" autocomplete="current-password"></label>
              <button class="btn primary full" type="submit">Sign In →</button>
              <div class="ctr">Don’t have an account? <a href="/register">Sign Up</a></div>
              <div class="copy">© 2026 YEP. All rights reserved.</div>
            </form>
            """.formatted(errorHtml);
        sendHtml(ex, 200, authPage("Welcome Back", "Empowering Young People<br><b>Building a Better Tomorrow</b>",
                "<b>Learn. Grow. Lead.</b> Join a community of empowered youth creating lasting impact.", content));
    }

    static void loginPost(HttpExchange ex) throws IOException {
        Map<String, String> f = parseForm(ex);
        try {
            User u = AUTH.login(f.get("email"), f.get("password"));
            if (u instanceof YouthParticipant && !"VERIFIED".equals(YOUTH_AGE_STATUS.get(u.getId()))) {
                loginGet(ex, "Your account is awaiting manual age verification.");
                return;
            }
            String sid = UUID.randomUUID().toString();
            SESSIONS.put(sid, u);
            setSessionCookie(ex, sid);
            setFlash(ex, "Logged in successfully.");
            redirect(ex, "/dashboard");
        } catch (Exception e) {
            loginGet(ex, e.getMessage());
        }
    }

    static void ageVerificationGet(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        if (user.getRole() != UserRole.ADMIN) {
            sendHtml(ex, 403, page("Forbidden", user, "/age-verification",
                    "<div class='card'><h1>Administrator access required</h1></div>"));
            return;
        }

        List<User> pending = USERS.findAll().stream()
                .filter(u -> u instanceof YouthParticipant)
                .filter(u -> "PENDING".equals(YOUTH_AGE_STATUS.get(u.getId())))
                .collect(Collectors.toList());
        StringBuilder rows = new StringBuilder();
        if (pending.isEmpty()) {
            rows.append("<div class='empty-state'><p>No youth registrations are awaiting age review.</p></div>");
        } else {
            for (User applicant : pending) {
                YouthParticipant youth = (YouthParticipant) applicant;
                LocalDate dob = YOUTH_DATE_OF_BIRTH.get(youth.getId());
                rows.append("<div class='card'><div class='flex-between'><div><h2>")
                        .append(esc(youth.getName())).append("</h2><p class='muted'>").append(esc(youth.getEmail()))
                        .append("</p></div><span class='badge warning'>Pending review</span></div>")
                        .append("<p><strong>Date of birth:</strong> ").append(fmtDate(dob))
                        .append(" &nbsp; <strong>Calculated age:</strong> ").append(youth.getAge()).append("</p>")
                        .append("<p class='muted'>Compare the birth date with official proof of age presented to the YEP team. Do not copy or upload the document.</p>")
                        .append("<form method='post' action='/age-verification/review' class='flex-row'>")
                        .append("<input type='hidden' name='userId' value='").append(youth.getId()).append("'>")
                        .append("<button class='btn primary' type='submit' name='decision' value='approve'>Proof checked — approve</button>")
                        .append("<button class='btn' type='submit' name='decision' value='reject' style='color:#dc2626'>Reject registration</button>")
                        .append("</form></div>");
            }
        }
        String content = "<div class='page-header'><h1>Youth age verification</h1>"
                + "<p class='muted'>Review proof of age before allowing a youth participant to sign in.</p></div>"
                + "<div class='alert' style='background:#dbe7ff;border:1px solid #c5d6ff'>"
                + "Only view an official document with the applicant. Do not request or retain document numbers, scans, or photos.</div>"
                + rows;
        sendHtml(ex, 200, page("Age Verification", user, "/age-verification", content));
    }

    static void ageVerificationReviewPost(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        if (user.getRole() != UserRole.ADMIN) {
            sendHtml(ex, 403, page("Forbidden", user, "/age-verification",
                    "<div class='card'><h1>Administrator access required</h1></div>"));
            return;
        }
        Map<String, String> form = parseForm(ex);
        try {
            long userId = Long.parseLong(form.getOrDefault("userId", ""));
            User applicant = USERS.findById(userId)
                    .filter(u -> u instanceof YouthParticipant)
                    .filter(u -> "PENDING".equals(YOUTH_AGE_STATUS.get(u.getId())))
                    .orElseThrow(() -> new ValidationException("Pending registration not found."));
            String decision = form.get("decision");
            if ("approve".equals(decision)) {
                LocalDate dob = YOUTH_DATE_OF_BIRTH.get(userId);
                LocalDate registeredOn = YOUTH_REGISTERED_ON.get(userId);
                if (dob == null || registeredOn == null) {
                    throw new ValidationException("Age verification details are unavailable; registration cannot be approved.");
                }
                int age = ageOn(dob, registeredOn);
                ((YouthParticipant) applicant).setAge(age);
                applicant.activate();
                YOUTH_AGE_STATUS.put(userId, "VERIFIED");
                YOUTH_DATE_OF_BIRTH.remove(userId);
                YOUTH_REGISTERED_ON.remove(userId);
                AGE_REVIEWED_BY.put(userId, user.getId());
                AGE_REVIEWED_AT.put(userId, LocalDateTime.now());
                setFlash(ex, "Age verified. " + applicant.getName() + " can now sign in.");
            } else if ("reject".equals(decision)) {
                USERS.delete(userId);
                YOUTH_AGE_STATUS.remove(userId);
                YOUTH_DATE_OF_BIRTH.remove(userId);
                YOUTH_REGISTERED_ON.remove(userId);
                AGE_REVIEWED_BY.remove(userId);
                AGE_REVIEWED_AT.remove(userId);
                setFlash(ex, "Registration rejected and pending account data deleted.");
            } else {
                throw new ValidationException("Choose approve or reject.");
            }
        } catch (Exception e) {
            setFlash(ex, e.getMessage(), true);
        }
        redirect(ex, "/age-verification");
    }

    static LocalDate parseDateOfBirth(String value) {
        if (value == null || value.isBlank()) throw new ValidationException("Date of birth is required for youth registration.");
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ValidationException("Enter a valid date of birth.");
        }
    }

    static int ageOn(LocalDate dob, LocalDate asOf) {
        if (dob == null || dob.isAfter(asOf)) throw new ValidationException("Date of birth cannot be in the future.");
        int age = Period.between(dob, asOf).getYears();
        if (age < 18 || age > 35) throw new ValidationException("Youth participants must be 18–35 on the registration date.");
        return age;
    }

    static void logout(HttpExchange ex) throws IOException {
        String sid = readCookie(ex, "YEP_SESSION");
        if (sid != null) SESSIONS.remove(sid);
        ex.getResponseHeaders().add("Set-Cookie", "YEP_SESSION=; Path=/; Max-Age=0");
        redirect(ex, "/");
    }

    static void dashboard(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        List<LearningActivity> myActs = LEARNING.forUser(user.getId());
        Optional<Team> myTeam = TEAM_SVC.findByMember(user);
        List<Certificate> certs = CERTS.findAll().stream()
                .filter(c -> c.getUserId().equals(user.getId())).collect(Collectors.toList());
        List<DiscussionThread> recentThreads = DISCUSS.all();

        StringBuilder actHtml = new StringBuilder();
        if (myActs.isEmpty()) {
            actHtml.append("<div class='empty-state'><p>You haven't enrolled in any activity yet.</p><a class='btn primary' href='/activities'>Browse Activities</a></div>");
        } else {
            actHtml.append("<ul class='list'>");
            for (LearningActivity a : myActs) {
                actHtml.append("<li><div><strong>").append(esc(a.getTitle())).append("</strong><br><span class='badge'>Level ").append(a.getLevel().getValue()).append("</span> <span class='muted'>").append(esc(a.getCategory())).append("</span></div></li>");
            }
            actHtml.append("</ul>");
        }

        StringBuilder teamHtml = new StringBuilder();
        if (myTeam.isEmpty()) {
            teamHtml.append("<div class='empty-state'><p>You are not in a team.</p><a class='btn' href='/level-two'>Explore Level Two</a></div>");
        } else {
            Team t = myTeam.get();
            teamHtml.append("<div class='mb-4'><h3>").append(esc(t.getTeamName())).append(" <span class='badge success'>").append(t.getStatus()).append("</span></h3>");
            if (t.getMentorId() != null) {
                USERS.findById(t.getMentorId()).ifPresent(m -> teamHtml.append("<p class='muted'>Mentor: <strong>").append(esc(m.getName())).append("</strong></p>"));
            }
            teamHtml.append("</div><ul class='list compact'>");
            for (TeamMember m : t.getMembers())
                teamHtml.append("<li>").append(esc(m.getUserName())).append(" <span class='muted'>(").append(m.getRoleInTeam()).append(")</span></li>");
            teamHtml.append("</ul>");
            Optional<ImpactProject> proj = PROJECTS.findAll().stream().filter(p -> p.getTeamId().equals(t.getId())).findFirst();
            proj.ifPresent(p -> teamHtml.append("<div class='mt-4'><h4>Project: ").append(esc(p.getTitle())).append("</h4><span class='badge warning'>").append(p.getStatus()).append("</span></div>"));
        }

        StringBuilder discHtml = new StringBuilder();
        if (recentThreads.isEmpty()) {
            discHtml.append("<div class='empty-state'><p>No discussions yet.</p><a class='btn' href='/discussion'>Start a Discussion</a></div>");
        } else {
            discHtml.append("<ul class='list compact'>");
            for (DiscussionThread t : recentThreads.subList(0, Math.min(5, recentThreads.size())))
                discHtml.append("<li><a href='/thread?id=").append(t.getId()).append("'><strong>").append(esc(t.getTitle())).append("</strong></a><br><span class='muted'>").append(esc(t.getAuthorName())).append(" · ").append(t.getReplyCount()).append(" replies</span></li>");
            discHtml.append("</ul><a href='/discussion' class='muted mt-2 inline-block'>View all →</a>");
        }

        StringBuilder certHtml = new StringBuilder();
        if (certs.isEmpty()) {
            certHtml.append("<div class='empty-state'><p>No certificates earned yet.</p></div>");
        } else {
            certHtml.append("<ul class='list compact'>");
            for (Certificate c : certs)
                certHtml.append("<li><strong>").append(esc(c.getTitle())).append("</strong><br><span class='muted'>#").append(c.getCertificateNumber()).append("</span></li>");
            certHtml.append("</ul><a href='/certificate' class='muted mt-2 inline-block'>View Certificates →</a>");
        }

        String content =
            "<div class='flex-between mb-4'>" +
                "<div><h1 class='mb-0'>Dashboard</h1><p class='muted mt-1'>" + esc(user.getDashboardSummary()) + "</p></div>" +
            "</div>" +
            "<div class='grid-layout'>" +
                "<div class='grid-main'>" +
                    "<div class='card'><h2>📚 My Activities</h2>" + actHtml + "</div>" +
                    "<div class='card'><h2>👥 My Team & Project</h2>" + teamHtml + "</div>" +
                "</div>" +
                "<div class='grid-side'>" +
                    "<div class='card'><h2>💬 Recent Discussions</h2>" + discHtml + "</div>" +
                    "<div class='card'><h2>🏅 Achievements</h2>" + certHtml + "</div>" +
                "</div>" +
            "</div>";

        sendHtml(ex, 200, page("Dashboard", user, "/dashboard", content));
    }

    static void activities(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        String cat = q.getOrDefault("category", "");
        String lvl = q.getOrDefault("level", "");

        List<LearningActivity> all = ACTIVITIES.findAll().stream().filter(LearningActivity::isPublished).collect(Collectors.toList());
        List<LearningActivity> filtered = all.stream()
                .filter(a -> cat.isEmpty() || cat.equals(a.getCategory()))
                .filter(a -> lvl.isEmpty() || Integer.parseInt(lvl) == a.getLevel().getValue())
                .collect(Collectors.toList());

        Set<String> cats = all.stream().map(LearningActivity::getCategory).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        StringBuilder catOpts = new StringBuilder("<option value=''>All categories</option>");
        for (String c : cats) catOpts.append("<option value='").append(esc(c)).append("'").append(c.equals(cat) ? " selected" : "").append(">").append(esc(c)).append("</option>");

        StringBuilder list = new StringBuilder();
        if (filtered.isEmpty()) {
            list.append("<div class='empty-state'><h3>No activities found</h3><p>Try clearing your filters.</p><a class='btn' href='/activities'>Clear Filters</a></div>");
        } else {
            for (LearningActivity a : filtered) {
                list.append("<div class='card flex-between'>")
                    .append("<div style='flex:1'>")
                    .append("<h2>").append(esc(a.getTitle())).append(" <span class='badge'>Level ").append(a.getLevel().getValue()).append("</span></h2>")
                    .append("<p class='muted mb-2'>").append(esc(a.getDescription())).append("</p>")
                    .append("<p class='muted' style='font-size:0.8rem'>").append(a.getType()).append(" · ").append(esc(a.getCategory())).append(" · ").append(fmtDate(a.getStartDate())).append("</p>")
                    .append("</div>")
                    .append("<div style='margin-left:20px'>");
                if (a.getLevel().getValue() == 1) {
                    boolean enrolled = ENROLLS.findAll().stream().anyMatch(e -> e.getUserId().equals(user.getId()) && e.getActivityId().equals(a.getId()));
                    if (enrolled) list.append("<span class='badge success'>Enrolled ✓</span>");
                    else list.append("<form method='post' action='/enroll'><input type='hidden' name='activityId' value='").append(a.getId()).append("'><button class='btn primary' type='submit'>Enroll</button></form>");
                } else {
                    list.append("<a class='btn' href='/level-two'>Go to Level 2</a>");
                }
                list.append("</div></div>");
            }
        }

        String content =
            "<h1>Explore Activities</h1>" +
            "<div class='card' style='padding:16px;margin-bottom:24px'>" +
                "<form method='get' action='/activities' class='flex-row'>" +
                    "<div style='flex:1'><label>Category</label><select name='category' onchange='this.form.submit()'>" + catOpts + "</select></div>" +
                    "<div style='flex:1'><label>Level</label><select name='level' onchange='this.form.submit()'>" +
                        "<option value=''>All levels</option>" +
                        "<option value='1'" + ("1".equals(lvl) ? " selected" : "") + ">Level One</option>" +
                        "<option value='2'" + ("2".equals(lvl) ? " selected" : "") + ">Level Two</option>" +
                    "</select></div>" +
                "</form>" +
            "</div>" + list;

        sendHtml(ex, 200, page("Activities", user, "/activities", content));
    }

    static void enroll(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> f = parseForm(ex);
        try {
            Long aid = Long.parseLong(f.get("activityId"));
            LearningActivity a = ACTIVITIES.findById(aid).orElseThrow(() -> new ValidationException("Activity not found"));
            LEARNING.enroll(user, a);
            setFlash(ex, "Successfully enrolled in " + a.getTitle());
            redirect(ex, "/dashboard");
        } catch (Exception e) {
            setFlash(ex, "Enrollment failed: " + e.getMessage(), true);
            redirect(ex, "/activities");
        }
    }

    static void levelOne(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        List<LearningActivity> list = LEARNING.byLevel(1);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='page-header'><h1>Level One — Virtual Exchange</h1><p class='muted'>Large-group online sessions covering digital literacy, AI, critical thinking, media literacy, and intercultural communication.</p></div>");

        sb.append("<div class='card'><h2>Available Sessions</h2>");
        if (list.isEmpty()) sb.append("<p class='muted'>No Level One sessions yet.</p>");
        else {
            for (LearningActivity a : list) {
                sb.append("<div class='list-item'>")
                  .append("<div><strong>").append(esc(a.getTitle())).append("</strong><br><span class='muted'>").append(a.getType()).append(" · ").append(esc(a.getCategory())).append("</span></div>");
                boolean enrolled = ENROLLS.findAll().stream().anyMatch(e -> e.getUserId().equals(user.getId()) && e.getActivityId().equals(a.getId()));
                if (enrolled) sb.append("<span class='badge success'>Enrolled ✓</span>");
                else sb.append("<form method='post' action='/enroll'><input type='hidden' name='activityId' value='").append(a.getId()).append("'><button class='btn primary small'>Join</button></form>");
                sb.append("</div>");
            }
        }
        sb.append("</div>");

        Optional<LearningActivity> liveSession = ACTIVITIES.findAll().stream()
                .filter(a -> a.getType() == ActivityType.LIVE_SESSION)
                .findFirst();
        sb.append("<div class='card'><div class='flex-between mb-4'><h2>Live Virtual Exchange</h2><a class='btn primary small' href='/live-session'>Open live room</a></div>");
        if (liveSession.isPresent()) {
            LearningActivity live = liveSession.get();
            sb.append("<p><strong>").append(esc(live.getTitle())).append("</strong></p>")
              .append("<p class='muted mb-2'>").append(esc(live.getDescription())).append("</p>")
              .append("<p class='muted'>Date: ").append(fmtDate(live.getStartDate())).append(" · Category: ").append(esc(live.getCategory())).append("</p>");
            if (live.getContentUrl() != null && !live.getContentUrl().isBlank()) {
                sb.append("<p class='mt-2'><a class='btn primary' href='/live-session'>Join now</a></p>");
            }
        } else {
            sb.append("<p class='muted'>There is no live session scheduled right now.</p>");
        }
        sb.append("</div>");

        sb.append("<div class='card'><div class='flex-between mb-4'><h2>Open Discussions</h2><a class='btn primary small' href='/discussion'>New Post</a></div>");
        List<DiscussionThread> threads = DISCUSS.all();
        if (threads.isEmpty()) sb.append("<p class='muted'>Start the first discussion.</p>");
        else {
            sb.append("<ul class='list'>");
            for (DiscussionThread t : threads) sb.append("<li><a href='/thread?id=").append(t.getId()).append("'><strong>").append(esc(t.getTitle())).append("</strong></a><br><span class='muted'>").append(esc(t.getAuthorName())).append(" · ").append(t.getReplyCount()).append(" replies</span></li>");
            sb.append("</ul>");
        }
        sb.append("</div>");

        sendHtml(ex, 200, page("Level One", user, "/level-one", sb.toString()));
    }

    static void levelTwo(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        List<LearningActivity> list = LEARNING.byLevel(2);
        Optional<Team> myTeam = TEAM_SVC.findByMember(user);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='page-header'><h1>Level Two — Team Projects</h1><p class='muted'>Selected participants form teams of 3–5 with a mentor to build impact projects in employment, education, agriculture, health, climate change, or digital inclusion.</p></div>");

        sb.append("<div class='card'><h2>Available Exchanges</h2>");
        if (list.isEmpty()) sb.append("<p class='muted'>No Level Two exchanges yet.</p>");
        else {
            for (LearningActivity a : list) {
                sb.append("<div class='list-item'>")
                  .append("<div><strong>").append(esc(a.getTitle())).append("</strong><br><span class='muted'>").append(esc(a.getCategory())).append(" · ").append(fmtDate(a.getStartDate())).append(" → ").append(fmtDate(a.getEndDate())).append("</span></div>")
                  .append("</div>");
            }
        }
        sb.append("</div>");

        if (myTeam.isEmpty()) {
            sb.append("<div class='card'><h2>Create your team</h2>");
            if (user.isEligibleForLevelTwo()) {
                sb.append("<p class='muted mb-4'>You are eligible. Name your team and pick an exchange.</p>");
                sb.append("<form method='post' action='/team' class='flex-row'>")
                  .append("<div style='flex:1'><label>Team name</label><input name='teamName' required></div>")
                  .append("<div style='flex:1'><label>Exchange</label><select name='activityId'>");
                for (LearningActivity a : list) sb.append("<option value='").append(a.getId()).append("'>").append(esc(a.getTitle())).append("</option>");
                sb.append("</select></div>")
                  .append("<div style='align-self:flex-end'><button class='btn primary' type='submit'>Create</button></div>")
                  .append("</form>");
            } else {
                sb.append("<p class='muted'>Level Two is by invitation. Complete Level One and your facilitator will invite you.</p>");
            }
            sb.append("</div>");
        } else {
            Team t = myTeam.get();
            sb.append("<div class='card'><div class='flex-between'><h2>").append(esc(t.getTeamName())).append("</h2><span class='badge success'>").append(t.getStatus()).append("</span></div><ul class='list'>");
            for (TeamMember m : t.getMembers()) sb.append("<li>").append(esc(m.getUserName())).append(" <span class='muted'>(").append(m.getRoleInTeam()).append(")</span></li>");
            sb.append("</ul>");
            if (t.getMentorId() != null) {
                USERS.findById(t.getMentorId()).ifPresent(m -> sb.append("<p class='mt-2'>Mentor: <strong>").append(esc(m.getName())).append("</strong></p>"));
            }
            sb.append("</div>");
        }

        sendHtml(ex, 200, page("Level Two", user, "/level-two", sb.toString()));
    }

    static void teamPost(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> f = parseForm(ex);
        try {
            Long actId = Long.parseLong(f.get("activityId"));
            TEAM_SVC.createTeam(f.get("teamName"), actId, user);
            setFlash(ex, "Team created successfully!");
            redirect(ex, "/level-two");
        } catch (Exception e) {
            setFlash(ex, "Failed to create team: " + e.getMessage(), true);
            redirect(ex, "/level-two");
        }
    }

    static void discussionGet(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        String cat = q.getOrDefault("category", "");
        List<DiscussionThread> threads = DISCUSS.byCategory(cat.isEmpty() ? null : cat);

        String[] cats = {"AI Ethics","Media Literacy","Intercultural Communication","Digital Skills","Agriculture & Climate","Health","Employment & Entrepreneurship","Team Collaboration"};
        StringBuilder catOpts = new StringBuilder("<option value=''>Select a topic…</option>");
        for (String c : cats) catOpts.append("<option>").append(esc(c)).append("</option>");

        StringBuilder filterOpts = new StringBuilder("<option value=''>All categories</option>");
        for (String c : cats) filterOpts.append("<option ").append(c.equals(cat) ? "selected" : "").append(">").append(esc(c)).append("</option>");

        StringBuilder threadsHtml = new StringBuilder();
        if (threads.isEmpty()) threadsHtml.append("<div class='empty-state'><p>No threads yet — be the first to share an idea!</p></div>");
        else {
            threadsHtml.append("<ul class='list'>");
            for (DiscussionThread t : threads) {
                threadsHtml.append("<li><a href='/thread?id=").append(t.getId()).append("'><strong>").append(esc(t.getTitle())).append("</strong></a> <span class='badge'>").append(esc(t.getCategory())).append("</span>")
                    .append("<br><span class='muted'>").append(esc(t.getAuthorName())).append(" · ").append(t.getReplyCount()).append(" replies</span></li>");
            }
            threadsHtml.append("</ul>");
        }

        String content =
            "<h1>Discussion Board</h1>" +
            "<div class='grid-2 mt-4' style='align-items:start'>" +
                "<div class='card'><h2>Start a new discussion</h2>" +
                    "<form method='post' action='/discussion'>" +
                        "<label>Category</label><select name='category' required>" + catOpts + "</select>" +
                        "<label>Title</label><input name='title' required placeholder='What would you like to discuss?'>" +
                        "<label>Message</label><textarea name='body' rows='4' required placeholder='Share your thoughts...'></textarea>" +
                        "<button class='btn primary mt-2' type='submit'>Post discussion</button>" +
                    "</form>" +
                "</div>" +
                "<div class='card'><h2>Recent Threads</h2>" +
                    "<form method='get' action='/discussion' style='margin-bottom:16px'>" +
                        "<select name='category' onchange='this.form.submit()' style='padding:8px'>" + filterOpts + "</select>" +
                    "</form>" + threadsHtml +
                "</div>" +
            "</div>";

        sendHtml(ex, 200, page("Discussions", user, "/discussion", content));
    }

    static void discussionPost(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> f = parseForm(ex);
        try {
            DISCUSS.createThread(user, null, f.get("category"), f.get("title"), f.get("body"));
            setFlash(ex, "Discussion posted successfully!");
            redirect(ex, "/discussion");
        } catch (Exception e) {
            setFlash(ex, "Failed to post: " + e.getMessage(), true);
            redirect(ex, "/discussion");
        }
    }

    static void threadGet(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        Long id = Long.valueOf(q.getOrDefault("id", "0"));
        Optional<DiscussionThread> opt = THREADS.findById(id);
        if (opt.isEmpty()) { redirect(ex, "/discussion"); return; }
        DiscussionThread t = opt.get();

        StringBuilder replies = new StringBuilder();
        if (t.getPosts().isEmpty()) replies.append("<p class='muted mb-4'>No replies yet. Be the first!</p>");
        else for (DiscussionPost p : t.getPosts()) replies.append("<div class='card' style='background:#f8fafc'><p class='mb-1'><strong>").append(esc(p.getAuthorName())).append("</strong></p><p>").append(esc(p.getBody())).append("</p></div>");

        String content =
            "<a href='/discussion' class='btn small mb-4'>← Back to discussions</a>" +
            "<div class='card'>" +
                "<h1 class='mb-1'>" + esc(t.getTitle()) + "</h1>" +
                "<p class='muted mb-4'>Posted by " + esc(t.getAuthorName()) + " · Category: " + esc(t.getCategory()) + "</p>" +
                "<div style='font-size:1.1rem;line-height:1.6'>" + esc(t.getBody()) + "</div>" +
            "</div>" +
            "<h2 class='mt-4 mb-2'>Replies (" + t.getReplyCount() + ")</h2>" + replies +
            "<div class='card mt-4'>" +
                "<form method='post' action='/thread?id=" + t.getId() + "'>" +
                    "<label>Write a reply</label>" +
                    "<textarea name='body' rows='3' required placeholder='Type your reply here...'></textarea>" +
                    "<button class='btn primary mt-2' type='submit'>Post Reply</button>" +
                "</form>" +
            "</div>";

        sendHtml(ex, 200, page(t.getTitle(), user, "/discussion", content));
    }

    static void threadPost(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        Map<String, String> f = parseForm(ex);
        Long id = Long.valueOf(q.getOrDefault("id", "0"));
        try {
            THREADS.findById(id).ifPresent(t -> DISCUSS.reply(t, user, f.get("body")));
            setFlash(ex, "Reply posted.");
        } catch (Exception ignored) {}
        redirect(ex, "/thread?id=" + id);
    }

    static void liveSessionGet(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Optional<LearningActivity> liveOpt = ACTIVITIES.findAll().stream()
                .filter(a -> a.getType() == ActivityType.LIVE_SESSION)
                .findFirst();
        if (liveOpt.isEmpty()) {
            sendHtml(ex, 200, page("Live Session", user, "/live-session",
                    "<div class='card'><h1>Live Virtual Exchange</h1><p class='muted'>There is no live session scheduled right now.</p></div>"));
            return;
        }

        LearningActivity live = liveOpt.get();
        LocalDate today = LocalDate.now();
        LocalDate starts = live.getStartDate();
        LocalDate ends = live.getEndDate() == null ? starts : live.getEndDate();
        boolean scheduledToday = starts != null && ends != null && !today.isBefore(starts) && !today.isAfter(ends);
        boolean ended = ends != null && today.isAfter(ends);
        boolean enrolled = isEnrolled(user, live);
        boolean checkedIn = LIVE_ATTENDANCE.getOrDefault(live.getId(), Set.of()).contains(user.getId());
        int registrations = (int) ENROLLS.findAll().stream()
                .filter(e -> e.getActivityId().equals(live.getId())).count();
        int checkIns = LIVE_ATTENDANCE.getOrDefault(live.getId(), Set.of()).size();
        String roomUrl = safeHttpUrl(live.getContentUrl());

        String status = ended ? "Session ended" : scheduledToday ? "Room open today" : starts == null ? "Date to be confirmed" : "Upcoming";
        String statusClass = ended ? "badge" : scheduledToday ? "badge success" : "badge warning";
        StringBuilder content = new StringBuilder("<div class='page-header'><h1>Live Virtual Exchange</h1>")
                .append("<p class='muted'>Meet learners across East Africa in a scheduled, facilitator-led online session.</p></div>")
                .append("<div class='card'><div class='flex-between'><h2>").append(esc(live.getTitle())).append("</h2><span class='")
                .append(statusClass).append("'>").append(esc(status)).append("</span></div>")
                .append("<p class='muted'>").append(esc(live.getDescription())).append("</p>")
                .append("<div class='grid-2'>")
                .append("<div><p><strong>Session date:</strong> ").append(fmtDate(starts)).append("</p>")
                .append("<p><strong>Topic:</strong> ").append(esc(live.getCategory())).append("</p>")
                .append("<p><strong>Format:</strong> ").append(esc(live.getType().toString())).append("</p>")
                .append("<p><strong>Countdown:</strong> <span id='live-countdown' data-starts-at='")
                .append(starts == null ? "" : starts.toString()).append("' data-ends-at='")
                .append(ends == null ? "" : ends.toString()).append("'>")
                .append(starts == null ? "Date to be confirmed" : ended ? "Session has ended" : scheduledToday ? "Session day" : "Loading…")
                .append("</span></p></div>")
                .append("<div class='card' style='margin:0;background:#f8fafc'><h3>Live room</h3>")
                .append("<p><strong>Registered:</strong> ").append(registrations)
                .append(" &nbsp; <strong>Checked in:</strong> ").append(checkIns).append("</p>")
                .append("<p><strong>Your status:</strong> ").append(checkedIn ? "Checked in" : enrolled ? "Registered" : "Not registered").append("</p>");

        if (enrolled && scheduledToday && checkedIn && roomUrl != null) {
            content.append("<p class='mt-2'><a class='btn primary' href='").append(esc(roomUrl))
                    .append("' target='_blank' rel='noopener noreferrer'>Join live room</a></p>");
        } else if (enrolled && scheduledToday && !checkedIn) {
            content.append("<form method='post' action='/live-session/check-in' class='mt-2'>")
                    .append("<button class='btn primary' type='submit'>Check in and join</button></form>");
        } else if (!enrolled && !ended) {
            content.append("<form method='post' action='/live-session' class='mt-2'>")
                    .append("<button class='btn primary' type='submit'>Register for session</button></form>");
        } else if (enrolled && !ended) {
            content.append("<p class='muted'>The room link and attendance check-in will be available on the session date.</p>");
        } else {
            content.append("<p class='muted'>Registration and room access are closed for this session.</p>");
        }
        if (roomUrl == null) {
            content.append("<p class='muted'>The facilitator has not added a valid room link yet.</p>");
        }
        content.append("</div></div>")
                .append("<script>(function(){var el=document.getElementById('live-countdown');if(!el||!el.dataset.startsAt)return;")
                .append("var start=new Date(el.dataset.startsAt+'T00:00:00');var end=new Date((el.dataset.endsAt||el.dataset.startsAt)+'T00:00:00');")
                .append("var now=new Date();var today=new Date(now.getFullYear(),now.getMonth(),now.getDate());var days=Math.ceil((start-today)/86400000);")
                .append("if(today>end)el.textContent='Session has ended';else if(today>=start)el.textContent='Session day';")
                .append("else el.textContent=days+' day'+(days===1?'':'s')+' remaining';})();</script>");

        sendHtml(ex, 200, page("Live Session", user, "/live-session", content.toString()));
    }

    static void liveSessionPost(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Optional<LearningActivity> liveOpt = ACTIVITIES.findAll().stream()
                .filter(a -> a.getType() == ActivityType.LIVE_SESSION)
                .findFirst();
        if (liveOpt.isEmpty()) {
            setFlash(ex, "No live session is available right now.", true);
            redirect(ex, "/level-one");
            return;
        }
        try {
            LearningActivity live = liveOpt.get();
            if (live.getEndDate() != null && LocalDate.now().isAfter(live.getEndDate())) {
                throw new ValidationException("Registration is closed for this session.");
            }
            LEARNING.enroll(user, live);
            setFlash(ex, "You are registered for the live virtual exchange.");
            redirect(ex, "/live-session");
        } catch (Exception e) {
            setFlash(ex, e.getMessage(), true);
            redirect(ex, "/live-session");
        }
    }

    static void liveSessionCheckIn(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Optional<LearningActivity> liveOpt = ACTIVITIES.findAll().stream()
                .filter(a -> a.getType() == ActivityType.LIVE_SESSION)
                .findFirst();
        if (liveOpt.isEmpty()) {
            setFlash(ex, "No live session is available right now.", true);
            redirect(ex, "/live-session");
            return;
        }

        LearningActivity live = liveOpt.get();
        LocalDate today = LocalDate.now();
        LocalDate starts = live.getStartDate();
        LocalDate ends = live.getEndDate() == null ? starts : live.getEndDate();
        if (!isEnrolled(user, live)) {
            setFlash(ex, "Register for the session before checking in.", true);
        } else if (starts == null || ends == null || today.isBefore(starts) || today.isAfter(ends)) {
            setFlash(ex, "Attendance check-in is only available on the scheduled session date.", true);
        } else {
            LIVE_ATTENDANCE.computeIfAbsent(live.getId(), id -> ConcurrentHashMap.newKeySet()).add(user.getId());
            setFlash(ex, "Attendance recorded. You can now join the live room.");
        }
        redirect(ex, "/live-session");
    }

    static boolean isEnrolled(User user, LearningActivity activity) {
        return ENROLLS.findAll().stream()
                .anyMatch(e -> e.getUserId().equals(user.getId()) && e.getActivityId().equals(activity.getId()));
    }

    static String safeHttpUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    ? value : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void certificate(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        List<Certificate> certs = CERTS.findAll().stream().filter(c -> c.getUserId().equals(user.getId())).collect(Collectors.toList());

        StringBuilder html = new StringBuilder();
        if (certs.isEmpty()) {
            html.append("<div class='empty-state'><p>You haven't earned any certificates yet.</p><p class='muted'>Complete a Level 1 quiz or a Level 2 project to earn one.</p></div>");
        } else {
            for (Certificate c : certs) {
                html.append("<div class='card' style='border-left:6px solid #fbbf24;background:linear-gradient(to right, #ffffff, #fffdf5)'>")
                    .append("<h2 class='mb-1'>").append(esc(c.getTitle())).append("</h2>")
                    .append("<p class='mb-1'>Awarded to <strong>").append(esc(c.getParticipantName())).append("</strong></p>")
                    .append("<p class='muted' style='font-family:monospace;font-size:0.9rem'>Certificate ID: ").append(c.getCertificateNumber()).append("</p>")
                    .append("<p class='muted'>Issued on: ").append(fmtDate(c.getIssueDate().toLocalDate())).append("</p>")
                    .append("</div>");
            }
        }

        String content =
            "<div class='flex-between'><h1>My Certificates</h1></div>" + html +
            "<div class='card mt-4' style='max-width:500px'>" +
                "<h2>Verify a certificate</h2>" +
                "<p class='muted mb-4'>Enter a certificate ID to verify its authenticity.</p>" +
                "<form method='post' action='/certificate' class='flex-row'>" +
                    "<input name='number' required placeholder='e.g. YEP-123456-7890' style='flex:1'>" +
                    "<button class='btn primary' type='submit'>Verify</button>" +
                "</form>" +
            "</div>";

        sendHtml(ex, 200, page("Certificates", user, "/certificate", content));
    }

    // ==================== HTML HELPERS & UI TEMPLATE ====================

    static final String AUTH_CSS = """
        :root{--navy:#0a1f5c;--navy2:#071544;--royal:#1d4ed8;--yel:#fbbf24;--bg:#eef2fb;--card:#fff;--tx:#0f1b3d;--mut:#64708f;--bd:#e3e8f4;--soft:#eaf0ff}
        @media(prefers-color-scheme:dark){:root{--bg:#0b1226;--card:#131c36;--tx:#e8ecf8;--mut:#9aa6c6;--bd:#233055;--soft:#1a2547}}
        *{box-sizing:border-box}
        body{margin:0;background:var(--bg);color:var(--tx);font:15px/1.5 "Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}
        a{color:var(--royal);text-decoration:none;font-weight:600}
        :focus-visible{outline:3px solid var(--yel);outline-offset:2px}
        .auth{min-height:100vh;display:grid;place-items:center;padding:24px;background:linear-gradient(135deg,var(--soft),var(--bg))}
        .acard{display:grid;grid-template-columns:5fr 6fr;width:min(1000px,100%);background:var(--card);border-radius:24px;overflow:hidden;box-shadow:0 24px 60px rgba(10,31,92,.2)}
        .hero1{position:relative;background:linear-gradient(170deg,var(--navy),var(--navy2));color:#fff;padding:34px 32px 0;display:flex;flex-direction:column;gap:16px;overflow:hidden;min-height:600px}
        .logo{display:flex;align-items:center;gap:10px;color:#fff}
        .logo b{font-size:30px;letter-spacing:1px;line-height:1;display:block}
        .logo small{display:block;font-size:11px;color:#c9d4f5;line-height:1.25;margin-top:3px}
        .logo svg{flex:none}
        .hero1 h2{font-size:30px;line-height:1.15;margin:8px 0 0}
        .hero1 h2 b{color:var(--yel)}
        .hero1 p{color:#d3dcf7;font-size:14px;max-width:32ch;margin:0}
        .hero1 p b{display:block;color:#fff;font-size:15px}
        .sw{position:absolute;right:-70px;bottom:60px;width:300px;height:300px;border-radius:50%;border:30px solid var(--yel);border-left-color:transparent;border-bottom-color:transparent;transform:rotate(-25deg)}
        .crowd{margin-top:auto;position:relative;z-index:1;height:170px;display:flex;align-items:flex-end;justify-content:center;margin-inline:-32px;background:linear-gradient(0deg,#1d4ed8 0,transparent 100%)}
        .crowd span{font-size:84px;line-height:.9;margin:0 -6px}
        .form{padding:38px 40px 28px;display:flex;flex-direction:column;gap:12px;justify-content:center;min-width:0}
        .form h1{font-size:30px;color:var(--navy);margin:0}
        .form .sub{margin:-8px 0 4px;color:var(--mut)}
        .fld{display:flex;align-items:center;gap:10px;border:1px solid var(--bd);border-radius:12px;padding:0 14px;background:var(--card);margin:0;min-width:0}
        .fld input,.fld select{flex:1;min-width:0;width:100%;border:0;background:transparent;color:var(--tx);padding:12px 0;outline:0;font:inherit}
        .fld:focus-within{border-color:var(--royal);box-shadow:0 0 0 3px rgba(29,78,216,.15)}
        .fld select option{color:#000}
        .auth-fields{display:grid;gap:10px}
        .auth-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
        .hint{color:var(--mut);font-size:12px;margin:-4px 2px 0}
        .terms{display:flex;align-items:flex-start;gap:10px;font-size:13px;line-height:1.4;margin:0}
        .terms input{width:auto;margin:3px 0 0;accent-color:var(--royal);flex:none}
        .btn{border:0;border-radius:10px;padding:10px 18px;font:inherit;font-weight:700;background:var(--navy);color:#fff;display:inline-flex;align-items:center;justify-content:center;gap:6px;cursor:pointer}
        .btn.primary{background:var(--royal);color:#fff}
        .btn.full{width:100%;padding:13px}
        .btn:hover{filter:brightness(.95);text-decoration:none}
        .err{color:#dc2626;font-size:13px;min-height:18px;margin:0}
        .ctr{text-align:center;font-size:14px}
        .copy{text-align:center;color:var(--mut);font-size:12px;margin-top:auto;padding-top:6px}
        .auth-message{padding:48px 40px;display:flex;flex-direction:column;justify-content:center;gap:16px}
        .auth-message h1{font-size:30px;color:var(--navy);margin:0}
        .auth-message p{margin:0;color:var(--mut)}
        .auth-symbol{width:48px;height:48px;border-radius:50%;display:grid;place-items:center;background:#dcfce7;color:#16a34a;font-size:26px;font-weight:800}
        @media(prefers-color-scheme:dark){.form h1,.auth-message h1{color:#fff}}
        @media(max-width:760px){.auth{padding:14px}.acard{grid-template-columns:1fr}.hero1{min-height:0;padding:24px 22px 0}.hero1 h2{font-size:24px}.crowd{height:92px}.crowd span{font-size:56px}.sw{display:none}.form{padding:26px 22px 20px}.auth-message{padding:30px 22px}.auth-grid{grid-template-columns:1fr}}
        """;

    static String authPage(String title, String heading, String description, String form) {
        return "<!doctype html><html lang='en'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>"
                + "<title>" + esc(title) + " — YEP</title><style>" + AUTH_CSS + "</style></head><body>"
                + "<div class='auth'><div class='acard'><section class='hero1'>"
                + "<div class='logo'><svg viewBox='0 0 40 40' width='42' height='42' aria-hidden='true'>"
                + "<rect width='40' height='40' rx='12' fill='#1d4ed8'/><path d='M8 29 20 9l12 20' stroke='#fbbf24' stroke-width='4.5' fill='none' stroke-linecap='round' stroke-linejoin='round'/>"
                + "<circle cx='20' cy='27.5' r='3.2' fill='#fff'/><circle cx='31' cy='9' r='2.2' fill='#fbbf24'/></svg>"
                + "<div><b>YEP</b><small>Youth Empowerment<br>Management System</small></div></div>"
                + "<h2>" + heading + "</h2><p>" + description + "</p><div class='sw'></div>"
                + "<div class='crowd' aria-hidden='true'><span>👩🏿</span><span>👨🏿</span><span>👩🏾</span></div>"
                + "</section>" + form + "</div></div></body></html>";
    }

    static final String CSS = """
        :root {
            --primary: #1d4ed8; --primary-dark: #0a1f5c; --primary-light: #dbe7ff;
            --bg: #eef2fb; --card: #ffffff; --text: #0f1b3d; --text-muted: #64708f;
            --border: #e3e8f4; --danger: #dc2626; --warning: #fbbf24;
            --radius: 16px; --shadow: 0 2px 10px rgba(10,31,92,.05);
        }
        @media (prefers-color-scheme: dark) {
            :root {
                --bg: #0b1226; --card: #131c36; --text: #e8ecf8; --text-muted: #9aa6c6;
                --border: #233055; --primary-light: #1a2547; --shadow: 0 2px 10px rgba(0,0,0,.2);
            }
        }
        * { box-sizing: border-box; }
        body { margin: 0; font-family: "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; background: var(--bg); color: var(--text); line-height: 1.5; }
        a { color: var(--primary); text-decoration: none; }
        a:hover { text-decoration: underline; }
        h1, h2, h3, h4 { color: var(--text); margin: 0 0 16px 0; font-weight: 600; }
        h1 { font-size: 1.8rem; letter-spacing: -0.02em; } h2 { font-size: 1.3rem; } h3 { font-size: 1.1rem; }
        p { margin: 0 0 16px 0; }

        /* Layout */
        .topbar { background: var(--primary-dark); border-bottom: 1px solid var(--border); padding: 16px 24px; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 16px; box-shadow: var(--shadow); position: sticky; top: 0; z-index: 100; }
        .brand { font-weight: 700; font-size: 1.1rem; color: #fff; display: flex; align-items: center; gap: 8px; }
        .nav-links { display: flex; gap: 8px; flex-wrap: wrap; }
        .nav-links a { padding: 8px 12px; border-radius: 10px; color: #c9d4f5; font-weight: 500; font-size: 0.9rem; transition: all 0.2s; }
        .nav-links a:hover { background: rgba(255,255,255,.08); color: #fff; text-decoration: none; }
        .nav-links a.active { background: var(--primary); color: #fff; font-weight: 600; }
        .container { max-width: 1100px; margin: 32px auto; padding: 0 20px; }

        /* Components */
        .card { background: var(--card); border: 1px solid var(--border); border-radius: var(--radius); padding: 24px; margin-bottom: 24px; box-shadow: var(--shadow); }
        .btn { display: inline-flex; align-items: center; justify-content: center; padding: 10px 16px; border-radius: 8px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-weight: 500; font-size: 0.9rem; cursor: pointer; transition: all 0.2s; text-decoration: none; font-family: inherit; }
        .btn:hover { background: var(--bg); text-decoration: none; }
        .btn:active { transform: scale(0.98); }
        .btn.primary { background: var(--primary); border-color: var(--primary); color: white; }
        .btn.primary:hover { background: #1741ba; }
        .btn.small { padding: 6px 12px; font-size: 0.85rem; }

        label { display: block; margin-bottom: 6px; font-size: 0.85rem; font-weight: 500; color: var(--text-muted); }
        input, select, textarea { width: 100%; padding: 10px 14px; border: 1px solid var(--border); border-radius: 8px; font-size: 0.95rem; font-family: inherit; background: var(--card); transition: border-color 0.2s, box-shadow 0.2s; }
        input:focus, select:focus, textarea:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-light); }

        .badge { display: inline-block; padding: 2px 10px; border-radius: 999px; background: var(--bg); color: var(--text-muted); font-size: 0.75rem; font-weight: 600; border: 1px solid var(--border); }
        .badge.success { background: var(--primary-light); color: var(--primary-dark); border-color: var(--primary-light); }
        .badge.warning { background: #fef3c7; color: #92400e; border-color: #fde68a; }
        .alert { padding: 12px 16px; border-radius: 8px; margin-bottom: 20px; font-size: 0.9rem; }
        .alert.error { background: #fef2f2; border: 1px solid #fecaca; color: var(--danger); }
        .alert.success { background: var(--primary-light); border: 1px solid #bbf7d0; color: var(--primary-dark); }

        .grid { display: grid; gap: 24px; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); }
        .grid-layout { display: grid; grid-template-columns: 2fr 1fr; gap: 24px; }
        .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
        @media (max-width: 768px) { .grid-layout, .grid-2 { grid-template-columns: 1fr; } }

        .list { list-style: none; padding: 0; margin: 0; }
        .list li { padding: 12px 0; border-bottom: 1px solid var(--border); }
        .list li:last-child { border-bottom: none; padding-bottom: 0; }
        .list.compact li { padding: 8px 0; }

        .list-item { display: flex; justify-content: space-between; align-items: center; padding: 16px 0; border-bottom: 1px solid var(--border); }
        .list-item:last-child { border-bottom: none; }

        .flex-between { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
        .flex-row { display: flex; gap: 16px; align-items: flex-end; }
        .empty-state { text-align: center; padding: 32px 0; }

        .auth-box { max-width: 600px; margin: 40px auto; }
        .text-center { text-align: center; } .text-right { text-align: right; }
        .muted { color: var(--text-muted); font-size: 0.9rem; }
        .mt-1 { margin-top: 4px; } .mt-2 { margin-top: 8px; } .mt-4 { margin-top: 24px; }
        .mb-0 { margin-bottom: 0; } .mb-1 { margin-bottom: 4px; } .mb-2 { margin-bottom: 8px; } .mb-4 { margin-bottom: 24px; }

        /* Carousel */
        .carousel { position: relative; height: 400px; border-radius: 16px; overflow: hidden; margin-bottom: 32px; background: #000; box-shadow: 0 10px 15px -3px rgba(0,0,0,0.1); }
        .slide { position: absolute; inset: 0; opacity: 0; transition: opacity 0.8s ease-in-out; display: flex; align-items: center; justify-content: center; text-align: center; padding: 40px; background-size: cover; background-position: center; }
        .slide::before { content: ''; position: absolute; inset: 0; background: linear-gradient(to top, rgba(0,0,0,0.8), rgba(0,0,0,0.2)); }
        .slide.active { opacity: 1; z-index: 10; }
        .slide-content { color: #fff; max-width: 600px; position: relative; z-index: 2; transform: translateY(20px); transition: transform 0.8s; }
        .slide.active .slide-content { transform: translateY(0); }
        .slide h2 { color: #fff; font-size: 2.2rem; margin-bottom: 12px; text-shadow: 0 2px 4px rgba(0,0,0,0.5); }
        .slide p { opacity: 0.95; font-size: 1.1rem; text-shadow: 0 1px 2px rgba(0,0,0,0.5); }
        .carousel .dots { position: absolute; bottom: 20px; left: 50%; transform: translateX(-50%); display: flex; gap: 10px; z-index: 20; }
        .carousel .dots span { width: 12px; height: 12px; border-radius: 50%; background: rgba(255,255,255,0.4); cursor: pointer; transition: background 0.3s; }
        .carousel .dots span.active { background: #fff; }

        .footer { text-align: center; padding: 40px 20px; color: var(--text-muted); font-size: 0.85rem; border-top: 1px solid var(--border); margin-top: 40px; }
        """;

    static String page(String title, User user, String activePath, String content) {
        String sid = currentSessionId(user);
        return "<!DOCTYPE html><html lang='en'><head>" +
                "<meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>" + esc(title) + " — YEP</title>" +
                "<style>" + CSS + "</style>" +
                "</head><body>" +
                nav(user, activePath) +
                "<main class='container'>" + flashHtml(sid) + content + "</main>" +
                "<footer class='footer'>© 2026 Youth Empowerment Project · Built for East Africa</footer>" +
                "<script>" + CAROUSEL_JS + "</script>" +
                "</body></html>";
    }

    static String nav(User user, String activePath) {
        StringBuilder sb = new StringBuilder();
        sb.append("<header class='topbar'><a href='/dashboard' class='brand' style='text-decoration:none'>🌍 YEP Platform</a><nav class='nav-links'>");
        if (user == null) {
            sb.append("<a href='/' class='").append(activePath.equals("/") ? "active" : "").append("'>Home</a>");
            sb.append("<a href='/login' class='").append(activePath.equals("/login") ? "active" : "").append("'>Sign in</a>");
            sb.append("<a href='/register' class='btn primary small' style='margin-left:8px'>Join YEP</a>");
        } else {
            sb.append("<a href='/dashboard' class='").append(activePath.equals("/dashboard") ? "active" : "").append("'>Dashboard</a>");
            sb.append("<a href='/activities' class='").append(activePath.equals("/activities") ? "active" : "").append("'>Activities</a>");
            sb.append("<a href='/level-one' class='").append(activePath.equals("/level-one") ? "active" : "").append("'>Level 1</a>");
            sb.append("<a href='/live-session' class='").append(activePath.equals("/live-session") ? "active" : "").append("'>Live Session</a>");
            sb.append("<a href='/level-two' class='").append(activePath.equals("/level-two") ? "active" : "").append("'>Level 2</a>");
            sb.append("<a href='/discussion' class='").append(activePath.equals("/discussion") || activePath.equals("/thread") ? "active" : "").append("'>Discussions</a>");
            sb.append("<a href='/certificate' class='").append(activePath.equals("/certificate") ? "active" : "").append("'>Certificates</a>");
            if (user.getRole() == UserRole.ADMIN) {
                sb.append("<a href='/age-verification' class='").append(activePath.equals("/age-verification") ? "active" : "").append("'>Age verification</a>");
            }
            sb.append("<span style='border-left:1px solid var(--border);margin:0 8px'></span>");
            sb.append("<a href='/logout' style='color:var(--danger)'>Logout</a>");
        }
        sb.append("</nav></header>");
        return sb.toString();
    }

    static String carousel() {
        return "<div class='carousel' id='c'>" +
                slide("https://images.unsplash.com/photo-1523050854058-8df90110c9f1?q=80&w=1200&auto=format&fit=crop", "Level One — Learn Together", "Large-group virtual exchanges on AI, media literacy, and intercultural communication.", true) +
                slide("https://images.unsplash.com/photo-1516321318423-f06f85e504b3?q=80&w=1200&auto=format&fit=crop", "Level Two — Build Together", "Selected teams design impact projects with a mentor, across borders.", false) +
                slide("https://images.unsplash.com/photo-1524178232363-1fb2b075b655?q=80&w=1200&auto=format&fit=crop", "Discuss & Exchange", "Peer discussion, mentor feedback and cross-border dialogue.", false) +
                slide("https://images.unsplash.com/photo-1522071820081-009f0129c71c?q=80&w=1200&auto=format&fit=crop", "Recognised Achievements", "Verifiable digital certificates that employers and universities can trust.", false) +
                "<div class='dots' id='dots'></div>" +
                "</div>";
    }

    static String slide(String imgUrl, String title, String text, boolean active) {
        return "<div class='slide" + (active ? " active" : "") + "' style='background-image:url(" + imgUrl + ")'>" +
                "<div class='slide-content'>" +
                "<h2>" + esc(title) + "</h2>" +
                "<p>" + esc(text) + "</p>" +
                "</div></div>";
    }

    static final String CAROUSEL_JS = """
        (function(){
          var slides = document.querySelectorAll('.slide');
          var dotsEl = document.getElementById('dots');
          if (!slides.length || !dotsEl) return;
          var i = 0;
          slides.forEach(function(_, idx){
            var d = document.createElement('span');
            if (idx === 0) d.classList.add('active');
            d.addEventListener('click', function(){ go(idx); });
            dotsEl.appendChild(d);
          });
          function go(n){
            slides[i].classList.remove('active');
            dotsEl.children[i].classList.remove('active');
            i = (n + slides.length) % slides.length;
            slides[i].classList.add('active');
            dotsEl.children[i].classList.add('active');
          }
          setInterval(function(){ go(i + 1); }, 5000);
        })();
        """;

    // ==================== REQUEST HELPERS & FLASH ====================

    static String currentSessionId(User user) {
        if (user == null) return null;
        for (Map.Entry<String, User> entry : SESSIONS.entrySet()) {
            if (entry.getValue().equals(user)) return entry.getKey();
        }
        return null;
    }

    static void setFlash(HttpExchange ex, String msg) { setFlash(ex, msg, false); }
    static void setFlash(HttpExchange ex, String msg, boolean isError) {
        String sid = readCookie(ex, "YEP_SESSION");
        if (sid != null) FLASH_MESSAGES.put(sid, (isError ? "ERROR:" : "SUCCESS:") + msg);
    }

    static String flashHtml(String sid) {
        if (sid == null) return "";
        String msg = FLASH_MESSAGES.remove(sid);
        if (msg == null) return "";
        boolean isErr = msg.startsWith("ERROR:");
        String text = msg.substring(isErr ? 6 : 8);
        return "<div class='alert " + (isErr ? "error" : "success") + "'>" + esc(text) + "</div>";
    }

    static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    static String readCookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers) for (String c : h.split(";")) {
            c = c.trim();
            if (c.startsWith(name + "=")) return c.substring(name.length() + 1);
        }
        return null;
    }

    static void setSessionCookie(HttpExchange ex, String sid) {
        ex.getResponseHeaders().add("Set-Cookie", "YEP_SESSION=" + sid + "; Path=/; HttpOnly; Max-Age=86400; SameSite=Lax");
    }

    static User currentUser(HttpExchange ex) {
        String sid = readCookie(ex, "YEP_SESSION");
        return sid == null ? null : SESSIONS.get(sid);
    }

    static Map<String, String> parseForm(HttpExchange ex) throws IOException {
        return parseQuery(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return map;
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) map.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                else map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        return map;
    }

    static Integer parseIntOrNull(String s) {
        try { return s == null || s.isBlank() ? null : Integer.parseInt(s); } catch (Exception e) { return null; }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String fmtDate(LocalDate d) { return d == null ? "—" : DATE_FMT.format(d); }

    static String dropdownOptions(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) sb.append("<option>").append(esc(v)).append("</option>");
        return sb.toString();
    }

    // ==================== SEED DATA ====================

    static void seedData() {
        try {
            YouthParticipant alice = (YouthParticipant) AUTH.register("YOUTH", "Alice Nakato", "alice@yep.ug", "+256770000001", "password", 22, "Female", "Uganda", "Kampala", "BSc Computer Science", null, null);
            YouthParticipant bob = (YouthParticipant) AUTH.register("YOUTH", "Bob Okello", "bob@yep.ug", "+256770000002", "password", 26, "Male", "Uganda", "Gulu", "Diploma in Agriculture", null, null);
            YouthParticipant charlie = (YouthParticipant) AUTH.register("YOUTH", "Charlie Mwangi", "charlie@yep.ke", "+254780000003", "password", 24, "Male", "Kenya", "Nairobi", "BA Communications", null, null);
            for (YouthParticipant participant : List.of(alice, bob, charlie)) {
                YOUTH_AGE_STATUS.put(participant.getId(), "VERIFIED");
            }
            Facilitator david = (Facilitator) AUTH.register("FACILITATOR", "David Ssem", "david@yep.ug", "+256770000005", "password", null, null, null, null, null, "Digital Literacy", null);
            Mentor grace = (Mentor) AUTH.register("MENTOR", "Grace Achieng", "grace@yep.ug", "+256770000004", "password", null, null, null, null, null, "AgriTech", "Makerere University");
            AUTH.register("ADMIN", "YEP Admin", "admin@yep.ug", "+256770000006", "password", null, null, null, null, null, null, null);

            // Level 1 Activities
            LearningActivity ai101 = LEARNING.create(1, "AI Literacy Fundamentals", "Responsible AI, misinformation and critical thinking", ActivityType.SELF_PACED, "AI Literacy", LocalDate.now(), LocalDate.now().plusDays(14), "https://content.yep.ug/ai101", david.getId());
            LearningActivity story = LEARNING.create(1, "Digital Storytelling Workshop", "Video, audio and written storytelling for impact", ActivityType.WORKSHOP, "Media Literacy", LocalDate.now(), LocalDate.now().plusDays(21), "https://content.yep.ug/story", david.getId());
            LearningActivity live = LEARNING.create(1, "Intercultural Communication Live", "Live virtual exchange with peers in Kenya and Tanzania", ActivityType.LIVE_SESSION, "Intercultural Communication", LocalDate.now().plusDays(3), LocalDate.now().plusDays(3), "https://content.yep.ug/ic-live", david.getId());
            LEARNING.create(1, "Mastering Generative AI Tools", "Hands-on session with ChatGPT and Midjourney for digital creators.", ActivityType.SELF_PACED, "AI Literacy", LocalDate.now(), LocalDate.now().plusDays(30), "https://content.yep.ug/genai", david.getId());
            LEARNING.create(1, "Fighting Misinformation in East Africa", "Critical thinking and fact-checking strategies for social media.", ActivityType.LIVE_SESSION, "Media Literacy", LocalDate.now().plusDays(2), LocalDate.now().plusDays(2), "https://content.yep.ug/factcheck", david.getId());

            // Level 2 Activity
            LearningActivity l2Agri = LEARNING.create(2, "Level Two: AgriTech Impact Teams", "Team-based impact project development in agriculture", ActivityType.VIRTUAL_EXCHANGE, "Agriculture", LocalDate.now(), LocalDate.now().plusDays(60), "https://content.yep.ug/l2-agri", david.getId());

            // Enrollments
            LEARNING.enroll(alice, ai101);
            LEARNING.enroll(bob, ai101);

            // Level 2 Setup
            alice.setLevelTwoEligible(true);
            bob.setLevelTwoEligible(true);
            charlie.setLevelTwoEligible(true);

            Team agri = TEAM_SVC.createTeam("Agri Innovators", l2Agri.getId(), alice);
            TEAM_SVC.addMember(agri, bob);
            TEAM_SVC.addMember(agri, charlie);
            TEAM_SVC.assignMentor(agri, grace);

            ImpactProject proj = PROJ_SVC.create(agri, "Smart Agri Advisor", ChallengeArea.AGRICULTURE, "Mobile-first crop advisory service for smallholder farmers", LocalDate.now(), LocalDate.now().plusMonths(3));
            PROJ_SVC.addMilestone(proj, "Problem statement", LocalDate.now().plusDays(7));
            PROJ_SVC.addMilestone(proj, "MVP mockup", LocalDate.now().plusDays(30));

            // Discussions
            DiscussionThread t1 = DISCUSS.createThread(alice, ai101.getId(), "AI Ethics", "How do we spot deepfakes?", "I saw a video claiming to be a minister, but the lips did not match the audio. How can we verify?");
            DISCUSS.reply(t1, bob, "Cross-check the source on the ministry's official site and look for the same clip on 2+ reputable outlets.");
            DISCUSS.reply(t1, charlie, "Also check metadata using a tool like InVID and try a reverse image search.");

            DiscussionThread t2 = DISCUSS.createThread(bob, null, "Team Collaboration", "Looking for a developer for our HealthTech team!", "Hi everyone, my team is building a telemedicine app for rural clinics. We need a Java developer to help with the backend. Anyone interested?");
            DISCUSS.reply(t2, charlie, "I'd love to join! I have experience with Spring Boot and MySQL.");
            DISCUSS.reply(t2, alice, "Count me in. I can help with the UI/UX and front-end.");

            // Assessments & Certificates
            Assessment quiz = new Assessment(ai101.getId(), "AI Fundamentals Quiz", 100);
            ASSESS.assessAndCertify(new QuizAssessmentStrategy(), quiz, alice, 85);
            ASSESS.assessAndCertify(new QuizAssessmentStrategy(), quiz, bob, 42);

            System.out.println("[seed] " + USERS.findAll().size() + " users, " + ACTIVITIES.findAll().size() + " activities, " + TEAMS.findAll().size() + " team(s), " + THREADS.findAll().size() + " thread(s), " + CERTS.findAll().size() + " certificate(s)");
        } catch (Exception e) { e.printStackTrace(); }
    }
}

// ============================================================================
// ENUMS, EXCEPTIONS, UTILS, MODELS, FACTORIES, STRATEGIES, REPOS, SERVICES
// ============================================================================

enum UserRole { YOUTH("Youth Participant"), FACILITATOR("Facilitator"), MENTOR("Mentor"), ADMIN("Administrator"), PARTNER("Partner Organisation"); private final String label; UserRole(String l) { this.label = l; } public String getLabel() { return label; } }
enum AccountStatus { PENDING, ACTIVE, SUSPENDED }
enum ActivityType { LIVE_SESSION, SELF_PACED, WORKSHOP, VIRTUAL_EXCHANGE }
enum ActivityLevel { LEVEL_ONE(1, "Level One"), LEVEL_TWO(2, "Level Two"); private final int value; private final String desc; ActivityLevel(int v, String d) { this.value = v; this.desc = d; } public int getValue() { return value; } public String getDescription() { return desc; } public static ActivityLevel fromInt(int v) { return v == 1 ? LEVEL_ONE : LEVEL_TWO; } }
enum ChallengeArea { EMPLOYMENT("Employment"), EDUCATION("Education"), AGRICULTURE("Agriculture"), HEALTH("Health"), CLIMATE_CHANGE("Climate Change"), DIGITAL_INCLUSION("Digital Inclusion"); private final String label; ChallengeArea(String l) { this.label = l; } public String getLabel() { return label; } }
enum ProjectStatus { DRAFT, IN_PROGRESS, SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED }
enum EnrollmentStatus { IN_PROGRESS, COMPLETED, DROPPED }
enum NotificationType { EMAIL, SMS, IN_APP }

class YepException extends RuntimeException { public YepException(String m) { super(m); } public YepException(String m, Throwable c) { super(m, c); } }
class ValidationException extends YepException { public ValidationException(String m) { super(m); } }
class AuthenticationException extends YepException { public AuthenticationException(String m) { super(m); } }

final class ValidationUtil {
    private ValidationUtil() {}
    static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    static boolean isEmail(String s) { return s != null && s.matches("^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$"); }
    static boolean isPhone(String s) { return s != null && s.matches("^\\+?[0-9]{7,15}$"); }
    static boolean minLength(String s, int n) { return s != null && s.length() >= n; }
    static boolean isAge18to35(Integer a) { return a != null && a >= 18 && a <= 35; }
}
final class PasswordUtil {
    private PasswordUtil() {}
    static String generateSalt() { byte[] s = new byte[16]; new SecureRandom().nextBytes(s); return Base64.getEncoder().encodeToString(s); }
    static String hash(String pwd, String saltB64) {
        try { MessageDigest md = MessageDigest.getInstance("SHA-256"); md.update(Base64.getDecoder().decode(saltB64)); return Base64.getEncoder().encodeToString(md.digest(pwd.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new YepException("Hashing failed", e); }
    }
    static boolean verify(String pwd, String saltB64, String expectedHash) { return hash(pwd, saltB64).equals(expectedHash); }
}

abstract class BaseEntity {
    private static long SEQ = 1;
    private final Long id = SEQ++;
    private final LocalDateTime createdAt = LocalDateTime.now();
    public Long getId() { return id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public abstract String displayLabel();
    @Override public String toString() { return displayLabel(); }
}

class Profile { private String bio, photoUrl, languagePreference = "en"; public String getBio() { return bio; } public void setBio(String v) { this.bio = v; } public String getPhotoUrl() { return photoUrl; } public void setPhotoUrl(String v) { this.photoUrl = v; } public String getLanguagePreference() { return languagePreference; } public void setLanguagePreference(String v) { this.languagePreference = v; } }

abstract class User extends BaseEntity {
    private String name, email, phone, passwordHash, passwordSalt;
    private AccountStatus status = AccountStatus.PENDING;
    private final Profile profile = new Profile();
    protected User(String name, String email, String phone) { setName(name); setEmail(email); this.phone = phone; }
    public abstract UserRole getRole();
    public abstract boolean isEligibleForLevelTwo();
    public abstract String getDashboardSummary();
    public void activate() { this.status = AccountStatus.ACTIVE; }
    public void suspend() { this.status = AccountStatus.SUSPENDED; }
    public boolean isActive() { return status == AccountStatus.ACTIVE; }
    @Override public String displayLabel() { return getRole().getLabel() + " · " + name; }
    public String getName() { return name; }
    public void setName(String v) { if (ValidationUtil.isBlank(v)) throw new ValidationException("Name required"); this.name = v.trim(); }
    public String getEmail() { return email; }
    public void setEmail(String v) { if (!ValidationUtil.isEmail(v)) throw new ValidationException("Invalid email"); this.email = v.trim().toLowerCase(); }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public String getPasswordSalt() { return passwordSalt; }
    public void setPasswordSalt(String v) { this.passwordSalt = v; }
    public AccountStatus getStatus() { return status; }
    public Profile getProfile() { return profile; }
}

class YouthParticipant extends User {
    private Integer age; private String gender, country, district, educationLevel; private boolean levelTwoEligible;
    public YouthParticipant(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole() { return UserRole.YOUTH; }
    @Override public boolean isEligibleForLevelTwo() { return isActive() && levelTwoEligible && age != null && age >= 18 && age <= 35; }
    @Override public String getDashboardSummary() { return String.format("Youth · %s · Level-2 eligible: %s", country == null ? "—" : country, levelTwoEligible ? "YES" : "no"); }
    public Integer getAge() { return age; }
    public void setAge(Integer v) { if (v != null && (v < 18 || v > 35)) throw new ValidationException("Age must be 18–35"); this.age = v; }
    public String getGender() { return gender; } public void setGender(String v) { this.gender = v; }
    public String getCountry() { return country; } public void setCountry(String v) { this.country = v; }
    public String getDistrict() { return district; } public void setDistrict(String v) { this.district = v; }
    public String getEducationLevel() { return educationLevel; } public void setEducationLevel(String v) { this.educationLevel = v; }
    public boolean isLevelTwoEligible() { return levelTwoEligible; } public void setLevelTwoEligible(boolean v) { this.levelTwoEligible = v; }
}

class Facilitator extends User {
    private String expertise;
    public Facilitator(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole() { return UserRole.FACILITATOR; }
    @Override public boolean isEligibleForLevelTwo() { return false; }
    @Override public String getDashboardSummary() { return "Facilitator · " + getName() + (expertise != null ? " · " + expertise : ""); }
    public String getExpertise() { return expertise; } public void setExpertise(String v) { this.expertise = v; }
}
class Mentor extends User {
    private String expertise, organisation;
    public Mentor(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole() { return UserRole.MENTOR; }
    @Override public boolean isEligibleForLevelTwo() { return false; }
    @Override public String getDashboardSummary() { return "Mentor · " + getName() + (organisation != null ? " @ " + organisation : ""); }
    public String getExpertise() { return expertise; } public void setExpertise(String v) { this.expertise = v; }
    public String getOrganisation() { return organisation; } public void setOrganisation(String v) { this.organisation = v; }
}
class Administrator extends User {
    public Administrator(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole() { return UserRole.ADMIN; }
    @Override public boolean isEligibleForLevelTwo() { return false; }
    @Override public String getDashboardSummary() { return "Administrator · " + getName(); }
}
class PartnerOrganisation extends User {
    private String orgType;
    public PartnerOrganisation(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole() { return UserRole.PARTNER; }
    @Override public boolean isEligibleForLevelTwo() { return false; }
    @Override public String getDashboardSummary() { return "Partner · " + getName() + (orgType != null ? " (" + orgType + ")" : ""); }
    public String getOrgType() { return orgType; } public void setOrgType(String v) { this.orgType = v; }
}

abstract class LearningActivity extends BaseEntity {
    private String title, description, category, contentUrl; private ActivityType type; private LocalDate startDate, endDate; private boolean published; private Long facilitatorId; private int maxParticipants = 100;
    protected LearningActivity(String title, String desc, ActivityType type, String cat, LocalDate start, LocalDate end, String url) {
        if (ValidationUtil.isBlank(title)) throw new ValidationException("Title required");
        if (start != null && end != null && end.isBefore(start)) throw new ValidationException("End date must follow start date");
        this.title = title.trim(); this.description = desc; this.type = type; this.category = cat; this.startDate = start; this.endDate = end; this.contentUrl = url;
    }
    public abstract ActivityLevel getLevel();
    public abstract List<String> getLevelFeatures();
    public abstract boolean supportsTeamFormation();
    public void publish() { this.published = true; }
    @Override public String displayLabel() { return "[L" + getLevel().getValue() + "] " + title; }
    public String getTitle() { return title; } public String getDescription() { return description; }
    public ActivityType getType() { return type; } public String getCategory() { return category; }
    public LocalDate getStartDate() { return startDate; } public LocalDate getEndDate() { return endDate; }
    public String getContentUrl() { return contentUrl; } public boolean isPublished() { return published; }
    public Long getFacilitatorId() { return facilitatorId; } public void setFacilitatorId(Long v) { this.facilitatorId = v; }
    public int getMaxParticipants() { return maxParticipants; }
}
class LevelOneActivity extends LearningActivity {
    public LevelOneActivity(String t, String d, ActivityType ty, String c, LocalDate s, LocalDate e, String u) { super(t, d, ty, c, s, e, u); }
    @Override public ActivityLevel getLevel() { return ActivityLevel.LEVEL_ONE; }
    @Override public boolean supportsTeamFormation() { return false; }
    @Override public List<String> getLevelFeatures() { return List.of("Large-group online sessions", "Digital literacy & AI fundamentals", "Media literacy & critical thinking", "Intercultural communication", "Open discussion forum"); }
}
class LevelTwoActivity extends LearningActivity {
    private int maxTeams = 10, minTeamSize = 3, maxTeamSize = 5;
    public LevelTwoActivity(String t, String d, ActivityType ty, String c, LocalDate s, LocalDate e, String u) { super(t, d, ty, c, s, e, u); }
    @Override public ActivityLevel getLevel() { return ActivityLevel.LEVEL_TWO; }
    @Override public boolean supportsTeamFormation() { return true; }
    @Override public List<String> getLevelFeatures() { return List.of("Selected participants only", "Team formation (3–5 members)", "Mentor assigned per team", "Impact-project development", "Milestones, submissions & assessment", "Cross-border virtual exchange"); }
    public int getMaxTeams() { return maxTeams; } public int getMinTeamSize() { return minTeamSize; } public int getMaxTeamSize() { return maxTeamSize; }
}

class Enrollment extends BaseEntity {
    private Long userId, activityId; private double progress; private EnrollmentStatus status = EnrollmentStatus.IN_PROGRESS;
    public Enrollment(Long u, Long a) { this.userId = u; this.activityId = a; }
    public void updateProgress(double pct) { if (pct < 0 || pct > 100) throw new ValidationException("0–100 only"); this.progress = pct; if (pct >= 100) this.status = EnrollmentStatus.COMPLETED; }
    @Override public String displayLabel() { return "Enrollment #" + getId(); }
    public Long getUserId() { return userId; } public Long getActivityId() { return activityId; } public double getProgress() { return progress; } public EnrollmentStatus getStatus() { return status; }
}
class Assessment extends BaseEntity {
    private Long activityId; private String title; private int maxScore = 100, passScore = 50;
    public Assessment(Long a, String t, int max) { this.activityId = a; this.title = t; this.maxScore = max; this.passScore = (int) Math.ceil(max * 0.5); }
    public boolean isPassed(double score) { return score >= passScore; }
    @Override public String displayLabel() { return "Assessment: " + title; }
    public Long getActivityId() { return activityId; } public String getTitle() { return title; } public int getMaxScore() { return maxScore; } public int getPassScore() { return passScore; }
}
class Certificate extends BaseEntity {
    private String certificateNumber; private Long userId, activityId, projectId; private String participantName, title, qrCodeUrl; private LocalDateTime issueDate = LocalDateTime.now();
    public Certificate(Long uid, String name, String title) { this.userId = uid; this.participantName = name; this.title = title; this.certificateNumber = "YEP-" + System.currentTimeMillis() + "-" + (1000 + new Random().nextInt(9000)); this.qrCodeUrl = "https://verify.yep.ug/" + certificateNumber; }
    @Override public String displayLabel() { return "Certificate " + certificateNumber; }
    public String getCertificateNumber() { return certificateNumber; } public Long getUserId() { return userId; } public Long getActivityId() { return activityId; } public void setActivityId(Long v) { this.activityId = v; } public Long getProjectId() { return projectId; } public void setProjectId(Long v) { this.projectId = v; } public String getParticipantName() { return participantName; } public String getTitle() { return title; } public String getQrCodeUrl() { return qrCodeUrl; } public LocalDateTime getIssueDate() { return issueDate; }
}
class TeamMember extends BaseEntity {
    private Long teamId, userId; private String roleInTeam = "MEMBER", userName;
    public TeamMember(Long team, Long user, String name, String role) { this.teamId = team; this.userId = user; this.userName = name; this.roleInTeam = role; }
    @Override public String displayLabel() { return userName + " (" + roleInTeam + ")"; }
    public Long getTeamId() { return teamId; } public Long getUserId() { return userId; } public String getRoleInTeam() { return roleInTeam; } public String getUserName() { return userName; }
}
class Team extends BaseEntity {
    private String teamName, status = "FORMING"; private Long mentorId, levelTwoActivityId; private final List<TeamMember> members = new ArrayList<>();
    public Team(String name) { if (ValidationUtil.isBlank(name)) throw new ValidationException("Team name required"); this.teamName = name.trim(); }
    public void addMember(TeamMember m) { members.add(m); }
    @Override public String displayLabel() { return "Team: " + teamName; }
    public String getTeamName() { return teamName; } public String getStatus() { return status; } public void setStatus(String v) { this.status = v; } public Long getMentorId() { return mentorId; } public void setMentorId(Long v) { this.mentorId = v; } public Long getLevelTwoActivityId() { return levelTwoActivityId; } public void setLevelTwoActivityId(Long v) { this.levelTwoActivityId = v; } public List<TeamMember> getMembers() { return Collections.unmodifiableList(members); }
}
class ImpactProject extends BaseEntity {
    private Long teamId; private String title, description; private ChallengeArea challengeArea; private ProjectStatus status = ProjectStatus.DRAFT; private LocalDate startDate, endDate; private final List<Milestone> milestones = new ArrayList<>();
    public ImpactProject(Long team, String title, ChallengeArea area, String desc) { if (ValidationUtil.isBlank(title)) throw new ValidationException("Title required"); this.teamId = team; this.title = title.trim(); this.challengeArea = area; this.description = desc; }
    public void start(LocalDate s, LocalDate e) { this.startDate = s; this.endDate = e; this.status = ProjectStatus.IN_PROGRESS; }
    public void addMilestone(Milestone m) { milestones.add(m); }
    @Override public String displayLabel() { return "Project: " + title; }
    public Long getTeamId() { return teamId; } public String getTitle() { return title; } public ChallengeArea getChallengeArea() { return challengeArea; } public String getDescription() { return description; } public ProjectStatus getStatus() { return status; } public void setStatus(ProjectStatus v) { this.status = v; } public LocalDate getStartDate() { return startDate; } public LocalDate getEndDate() { return endDate; } public List<Milestone> getMilestones() { return Collections.unmodifiableList(milestones); }
}
class Milestone extends BaseEntity {
    private Long projectId; private String title, description; private LocalDate dueDate; private boolean completed;
    public Milestone(Long project, String title, LocalDate due) { this.projectId = project; this.title = title; this.dueDate = due; }
    public void complete() { this.completed = true; }
    @Override public String displayLabel() { return "Milestone: " + title; }
    public Long getProjectId() { return projectId; } public String getTitle() { return title; } public LocalDate getDueDate() { return dueDate; } public boolean isCompleted() { return completed; }
}
class Submission extends BaseEntity {
    private Long projectId; private String title, fileUrl, notes, feedback; private Double score;
    public Submission(Long p, String t, String url, String notes) { this.projectId = p; this.title = t; this.fileUrl = url; this.notes = notes; }
    public void grade(double s, String f) { this.score = s; this.feedback = f; }
    @Override public String displayLabel() { return "Submission: " + title; }
    public Long getProjectId() { return projectId; } public String getTitle() { return title; } public String getFileUrl() { return fileUrl; } public Double getScore() { return score; } public String getFeedback() { return feedback; }
}

class DiscussionPost extends BaseEntity {
    private Long threadId, authorId; private String authorName, body;
    public DiscussionPost(Long t, Long a, String name, String body) { this.threadId = t; this.authorId = a; this.authorName = name; this.body = body; }
    @Override public String displayLabel() { return authorName + ": " + body.substring(0, Math.min(40, body.length())); }
    public Long getThreadId() { return threadId; } public String getAuthorName() { return authorName; } public String getBody() { return body; }
}
class DiscussionThread extends BaseEntity {
    private Long activityId, authorId; private String authorName, category, title, body; private final List<DiscussionPost> posts = new ArrayList<>();
    public DiscussionThread(Long activity, Long author, String name, String cat, String title, String body) { if (ValidationUtil.isBlank(title)) throw new ValidationException("Thread title required"); if (ValidationUtil.isBlank(body)) throw new ValidationException("Thread body required"); this.activityId = activity; this.authorId = author; this.authorName = name; this.category = cat; this.title = title.trim(); this.body = body.trim(); }
    public void addPost(DiscussionPost p) { posts.add(p); }
    @Override public String displayLabel() { return "Thread: " + title; }
    public Long getActivityId() { return activityId; } public String getAuthorName() { return authorName; } public String getCategory() { return category; } public String getTitle() { return title; } public String getBody() { return body; } public List<DiscussionPost> getPosts() { return Collections.unmodifiableList(posts); } public int getReplyCount() { return posts.size(); }
}

abstract class Notification extends BaseEntity {
    private Long recipientId; private String subject, message;
    protected Notification(Long r, String s, String m) { this.recipientId = r; this.subject = s; this.message = m; }
    public abstract NotificationType getType(); public abstract void send();
    @Override public String displayLabel() { return "[" + getType() + "] " + subject; }
    public Long getRecipientId() { return recipientId; } public String getSubject() { return subject; } public String getMessage() { return message; }
}
class EmailNotification extends Notification {
    public EmailNotification(Long r, String s, String m) { super(r, s, m); }
    @Override public NotificationType getType() { return NotificationType.EMAIL; }
    @Override public void send() { System.out.println("    [EMAIL → " + getRecipientId() + "] " + getSubject()); }
}
class SmsNotification extends Notification {
    public SmsNotification(Long r, String s, String m) { super(r, s, m); }
    @Override public NotificationType getType() { return NotificationType.SMS; }
    @Override public void send() { System.out.println("    [SMS   → " + getRecipientId() + "] " + getMessage()); }
}
class InAppNotification extends Notification {
    public InAppNotification(Long r, String s, String m) { super(r, s, m); }
    @Override public NotificationType getType() { return NotificationType.IN_APP; }
    @Override public void send() { System.out.println("    [INAPP → " + getRecipientId() + "] " + getSubject()); }
}

final class UserFactory {
    private UserFactory() {}
    static User create(String roleName, String name, String email, String phone) {
        return switch (UserRole.valueOf(roleName)) {
            case YOUTH -> new YouthParticipant(name, email, phone); case FACILITATOR -> new Facilitator(name, email, phone); case MENTOR -> new Mentor(name, email, phone); case ADMIN -> new Administrator(name, email, phone); case PARTNER -> new PartnerOrganisation(name, email, phone);
        };
    }
}
final class ActivityFactory {
    private ActivityFactory() {}
    static LearningActivity create(int level, String t, String d, ActivityType ty, String cat, LocalDate s, LocalDate e, String url) {
        return switch (level) { case 1 -> new LevelOneActivity(t, d, ty, cat, s, e, url); case 2 -> new LevelTwoActivity(t, d, ty, cat, s, e, url); default -> throw new ValidationException("Level must be 1 or 2"); };
    }
}
final class NotificationFactory {
    private NotificationFactory() {}
    static Notification create(String channel, Long recipient, String subject, String msg) {
        return switch (channel.toUpperCase()) { case "EMAIL" -> new EmailNotification(recipient, subject, msg); case "SMS" -> new SmsNotification(recipient, subject, msg); default -> new InAppNotification(recipient, subject, msg); };
    }
}

interface AssessmentStrategy { double calculateScore(double rawScore); boolean isPassing(double finalScore); String getName(); }
class QuizAssessmentStrategy implements AssessmentStrategy { @Override public double calculateScore(double r) { return r; } @Override public boolean isPassing(double s) { return s >= 50; } @Override public String getName() { return "Quiz (50% to pass)"; } }
class ProjectAssessmentStrategy implements AssessmentStrategy { @Override public double calculateScore(double r) { return r * 1.1; } @Override public boolean isPassing(double s) { return s >= 60; } @Override public String getName() { return "Project (60% to pass, +10% bonus)"; } }
class PeerAssessmentStrategy implements AssessmentStrategy { @Override public double calculateScore(double r) { return r * 0.9; } @Override public boolean isPassing(double s) { return s >= 40; } @Override public String getName() { return "Peer (40% to pass, -10% penalty)"; } }

interface Repository<T extends BaseEntity> { T save(T entity); Optional<T> findById(Long id); List<T> findAll(); void delete(Long id); }
class InMemoryRepository<T extends BaseEntity> implements Repository<T> {
    private final Map<Long, T> store = new LinkedHashMap<>();
    @Override public T save(T e) { store.put(e.getId(), e); return e; }
    @Override public Optional<T> findById(Long id) { return Optional.ofNullable(store.get(id)); }
    @Override public List<T> findAll() { return new ArrayList<>(store.values()); }
    @Override public void delete(Long id) { store.remove(id); }
}

class AuthService {
    private final Repository<User> users;
    AuthService(Repository<User> users) { this.users = users; }
    User register(String role, String name, String email, String phone, String password, Integer age, String gender, String country, String district, String education, String expertise, String organisation) {
        if (ValidationUtil.isBlank(name)) throw new ValidationException("Name required");
        if (!ValidationUtil.isEmail(email)) throw new ValidationException("Invalid email");
        if (!ValidationUtil.minLength(password, 8)) throw new ValidationException("Password must be at least 8 characters.");
        if (users.findAll().stream().anyMatch(u -> u.getEmail().equalsIgnoreCase(email))) throw new ValidationException("Email already registered");
        User u = UserFactory.create(role, name, email, phone);
        if (u instanceof YouthParticipant y) {
            if (!ValidationUtil.isAge18to35(age)) throw new ValidationException("Youth must be aged 18–35");
            y.setAge(age); y.setGender(gender); y.setCountry(country); y.setDistrict(district); y.setEducationLevel(education);
        }
        if (u instanceof Mentor m) { m.setExpertise(expertise); m.setOrganisation(organisation); }
        if (u instanceof Facilitator f) { f.setExpertise(expertise); }
        String salt = PasswordUtil.generateSalt(); u.setPasswordSalt(salt); u.setPasswordHash(PasswordUtil.hash(password, salt)); u.activate();
        return users.save(u);
    }
    User login(String email, String password) { return users.findAll().stream().filter(u -> u.getEmail().equalsIgnoreCase(email)).filter(u -> PasswordUtil.verify(password, u.getPasswordSalt(), u.getPasswordHash())).findFirst().orElseThrow(() -> new AuthenticationException("Invalid email or password")); }
}
class LearningService {
    private final Repository<LearningActivity> activities; private final Repository<Enrollment> enrollments;
    LearningService(Repository<LearningActivity> a, Repository<Enrollment> e) { this.activities = a; this.enrollments = e; }
    LearningActivity create(int level, String title, String desc, ActivityType type, String category, LocalDate start, LocalDate end, String url, Long facilitatorId) {
        LearningActivity a = ActivityFactory.create(level, title, desc, type, category, start, end, url); a.setFacilitatorId(facilitatorId); a.publish(); return activities.save(a);
    }
    List<LearningActivity> byLevel(int level) { return activities.findAll().stream().filter(a -> a.getLevel().getValue() == level && a.isPublished()).collect(Collectors.toList()); }
    List<LearningActivity> byCategory(String cat) { return activities.findAll().stream().filter(a -> cat == null || cat.equals(a.getCategory())).filter(LearningActivity::isPublished).collect(Collectors.toList()); }
    Enrollment enroll(User user, LearningActivity activity) {
        if (!activity.isPublished()) throw new ValidationException("Cannot enroll: not published");
        if (enrollments.findAll().stream().anyMatch(e -> e.getUserId().equals(user.getId()) && e.getActivityId().equals(activity.getId()))) throw new ValidationException("Already enrolled");
        return enrollments.save(new Enrollment(user.getId(), activity.getId()));
    }
    List<LearningActivity> forUser(Long userId) { Set<Long> enrolled = enrollments.findAll().stream().filter(e -> e.getUserId().equals(userId)).map(Enrollment::getActivityId).collect(Collectors.toSet()); return activities.findAll().stream().filter(a -> enrolled.contains(a.getId())).collect(Collectors.toList()); }
}
class DiscussionService {
    private final Repository<DiscussionThread> threads; private final Repository<DiscussionPost> posts;
    DiscussionService(Repository<DiscussionThread> t, Repository<DiscussionPost> p) { this.threads = t; this.posts = p; }
    DiscussionThread createThread(User author, Long activityId, String category, String title, String body) { return threads.save(new DiscussionThread(activityId, author.getId(), author.getName(), category, title, body)); }
    DiscussionPost reply(DiscussionThread thread, User author, String body) { if (ValidationUtil.isBlank(body)) throw new ValidationException("Reply required"); DiscussionPost p = posts.save(new DiscussionPost(thread.getId(), author.getId(), author.getName(), body.trim())); thread.addPost(p); return p; }
    List<DiscussionThread> all() { return threads.findAll(); }
    List<DiscussionThread> byCategory(String cat) { return threads.findAll().stream().filter(t -> cat == null || cat.equals(t.getCategory())).collect(Collectors.toList()); }
}
class TeamService {
    private final Repository<Team> teams; private final Repository<User> users;
    TeamService(Repository<Team> teams, Repository<User> users) { this.teams = teams; this.users = users; }
    Team createTeam(String name, Long levelTwoActivityId, User leader) { Team t = new Team(name); t.setLevelTwoActivityId(levelTwoActivityId); t.setStatus("FORMING"); teams.save(t); t.addMember(new TeamMember(t.getId(), leader.getId(), leader.getName(), "LEADER")); return t; }
    void addMember(Team team, User user) { if (team.getMembers().size() >= 5) throw new ValidationException("Team is full (max 5)"); team.addMember(new TeamMember(team.getId(), user.getId(), user.getName(), "MEMBER")); }
    void assignMentor(Team team, Mentor mentor) { team.setMentorId(mentor.getId()); team.setStatus("ACTIVE"); }
    Optional<Team> findByMember(User user) { return teams.findAll().stream().filter(t -> t.getMembers().stream().anyMatch(m -> m.getUserId().equals(user.getId()))).findFirst(); }
}
class ProjectService {
    private final Repository<ImpactProject> projects;
    ProjectService(Repository<ImpactProject> p) { this.projects = p; }
    ImpactProject create(Team team, String title, ChallengeArea area, String desc, LocalDate start, LocalDate end) { ImpactProject p = new ImpactProject(team.getId(), title, area, desc); p.start(start, end); return projects.save(p); }
    Milestone addMilestone(ImpactProject project, String title, LocalDate due) { Milestone m = new Milestone(project.getId(), title, due); project.addMilestone(m); return m; }
    Submission submit(ImpactProject project, String title, String fileUrl, String notes) { Submission s = new Submission(project.getId(), title, fileUrl, notes); project.setStatus(ProjectStatus.SUBMITTED); return s; }
}
class AssessmentService {
    private final Repository<Certificate> certificates;
    AssessmentService(Repository<Certificate> c) { this.certificates = c; }
    Certificate assessAndCertify(AssessmentStrategy strategy, Assessment assessment, User participant, double rawScore) {
        double finalScore = strategy.calculateScore(rawScore); boolean passed = strategy.isPassing(finalScore);
        System.out.printf("[assess] %s | raw=%.1f final=%.1f passed=%s%n", strategy.getName(), rawScore, finalScore, passed);
        if (!passed) return null;
        Certificate c = new Certificate(participant.getId(), participant.getName(), assessment.getTitle()); c.setActivityId(assessment.getActivityId()); return certificates.save(c);
    }
}
class NotificationService { void notify(String channel, User recipient, String subject, String msg) { NotificationFactory.create(channel, recipient.getId(), subject, msg).send(); } }
