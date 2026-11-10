import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * =============================================================================
 * YEP — Youth Empowerment Project
 * Single-file Java web app (OOP core + embedded HTTP server + HTML UI)
 * =============================================================================
 * Run:    java YEPWebApp.java
 * Open:   http://localhost:8080/
 *
 * Demo logins (password: password):
 *   alice@yep.ug   (Youth participant)
 *   bob@yep.ug     (Youth participant)
 *   grace@yep.ug   (Mentor)
 *   david@yep.ug   (Facilitator)
 *   admin@yep.ug   (Administrator)
 * =============================================================================
 */
public class YEPWebApp {

    // ==================== GLOBAL STATE ====================
    static final int PORT = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
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
        System.out.println("  Press Ctrl+C to stop.");
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
                case "/dashboard"   -> dashboard(ex, user);
                case "/activities"  -> activities(ex, user);
                case "/enroll"      -> enroll(ex, user);
                case "/level-one"   -> levelOne(ex, user);
                case "/level-two"   -> levelTwo(ex, user);
                case "/team"        -> { if (method.equals("POST")) teamPost(ex, user); else redirect(ex, "/level-two"); }
                case "/discussion"  -> { if (method.equals("POST")) discussionPost(ex, user); else discussionGet(ex, user); }
                case "/thread"      -> { if (method.equals("POST")) threadPost(ex, user); else threadGet(ex, user); }
                case "/certificate" -> certificate(ex, user);
                default             -> sendHtml(ex, 404, page("Not found", user,
                        "<div class='card'><h1>404</h1><p>Page not found.</p><a class='btn' href='/'>Home</a></div>"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendHtml(ex, 500, page("Error", null,
                        "<div class='card'><h1>Oops</h1><pre>" + esc(e.toString()) + "</pre></div>"));
            } catch (Exception ignored) {}
        }
    }

    // ==================== PAGE HANDLERS ====================

    static void home(HttpExchange ex, User user) throws IOException {
        String content =
            carousel() +
            "<section class='grid'>" +
                "<div class='card'><h3>🎓 Digital Learning</h3><p>AI literacy, media literacy, and critical thinking — free and mobile-first.</p></div>" +
                "<div class='card'><h3>🌍 Virtual Exchange</h3><p>Level One large-group sessions and Level Two team projects with peers across East Africa.</p></div>" +
                "<div class='card'><h3>💬 Discussions</h3><p>Ask questions, share ideas, and get guidance from mentors and facilitators.</p></div>" +
                "<div class='card'><h3>🏅 Certificates</h3><p>Verifiable digital certificates that universities and employers can trust.</p></div>" +
            "</section>" +
            "<div class='card' style='text-align:center'>" +
                "<h2>Ready to join?</h2>" +
                "<p class='muted'>Learn together. Build together. Get recognised.</p>" +
                "<a class='btn primary' href='/register'>Create account</a> " +
                "<a class='btn' href='/login'>Sign in</a>" +
            "</div>";
        sendHtml(ex, 200, page("Welcome", user, content));
    }

    static void registerGet(HttpExchange ex, String error) throws IOException {
        String err = error == null ? "" : "<div class='alert error'>" + esc(error) + "</div>";
        String content =
            "<div class='card' style='max-width:560px;margin:40px auto'>" +
                "<h1>Create your YEP account</h1>" + err +
                "<form method='post' action='/register'>" +
                    "<label>Role</label>" +
                    "<select name='role' id='role' onchange='toggleRoleFields()' required>" +
                        "<option value='YOUTH'>Youth Participant (18–35)</option>" +
                        "<option value='MENTOR'>Mentor</option>" +
                        "<option value='FACILITATOR'>Facilitator</option>" +
                    "</select>" +
                    "<label>Full name</label><input name='name' required>" +
                    "<label>Email</label><input type='email' name='email' required>" +
                    "<label>Phone</label><input name='phone'>" +
                    "<label>Password (min 6 chars)</label><input type='password' name='password' minlength='6' required>" +
                    "<div id='youthFields'>" +
                        "<label>Age (18–35)</label><input type='number' name='age' min='18' max='35'>" +
                        "<label>Gender</label><select name='gender'><option value=''>Select…</option>" +
                            "<option>Female</option><option>Male</option><option>Other</option></select>" +
                        "<label>Country</label><select name='country'><option value=''>Select…</option>" +
                            dropdownOptions(new String[]{"Uganda","Kenya","Tanzania","Rwanda","Burundi","South Sudan","Ethiopia","Somalia"}) +
                            "</select>" +
                        "<label>District</label><input name='district' placeholder='e.g. Kampala'>" +
                        "<label>Highest education</label><select name='education'><option value=''>Select…</option>" +
                            dropdownOptions(new String[]{"Secondary","Certificate","Diploma","Bachelor's Degree","Master's Degree","Other"}) +
                            "</select>" +
                    "</div>" +
                    "<div id='mentorFields' style='display:none'>" +
                        "<label>Area of expertise</label><select name='expertise'><option value=''>Select…</option>" +
                            dropdownOptions(new String[]{"Digital Literacy","AI Ethics","AgriTech","HealthTech","Climate & Sustainability","Entrepreneurship","Education","Media & Journalism"}) +
                            "</select>" +
                        "<label>Organisation</label><input name='organisation'>" +
                    "</div>" +
                    "<button class='btn primary' type='submit' style='margin-top:16px;width:100%'>Create account</button>" +
                "</form>" +
                "<p class='muted' style='text-align:center;margin-top:14px'>Already have one? <a href='/login'>Sign in</a></p>" +
            "</div>" +
            "<script>" +
                "function toggleRoleFields(){" +
                    "var r=document.getElementById('role').value;" +
                    "document.getElementById('youthFields').style.display=r==='YOUTH'?'block':'none';" +
                    "document.getElementById('mentorFields').style.display=(r==='MENTOR'||r==='FACILITATOR')?'block':'none';" +
                "}" +
                "toggleRoleFields();" +
            "</script>";
        sendHtml(ex, 200, page("Register", null, content));
    }

    static void registerPost(HttpExchange ex) throws IOException {
        Map<String, String> f = parseForm(ex);
        try {
            User u = AUTH.register(
                f.get("role"), f.get("name"), f.get("email"), f.get("phone"), f.get("password"),
                parseIntOrNull(f.get("age")), f.get("gender"), f.get("country"),
                f.get("district"), f.get("education"),
                f.get("expertise"), f.get("organisation")
            );
            String sid = UUID.randomUUID().toString();
            SESSIONS.put(sid, u);
            setSessionCookie(ex, sid);
            redirect(ex, "/dashboard");
        } catch (Exception e) {
            registerGet(ex, e.getMessage());
        }
    }

    static void loginGet(HttpExchange ex, String error) throws IOException {
        String err = error == null ? "" : "<div class='alert error'>" + esc(error) + "</div>";
        String content =
            "<div class='card' style='max-width:420px;margin:40px auto'>" +
                "<h1>Sign in to YEP</h1>" + err +
                "<form method='post' action='/login'>" +
                    "<label>Email</label><input type='email' name='email' required>" +
                    "<label>Password</label><input type='password' name='password' required>" +
                    "<button class='btn primary' type='submit' style='margin-top:16px;width:100%'>Sign in</button>" +
                "</form>" +
                "<p class='muted' style='text-align:center;margin-top:14px'>" +
                    "No account? <a href='/register'>Register</a></p>" +
                "<div class='muted' style='margin-top:20px;padding-top:16px;border-top:1px solid #dfe5df;font-size:.82rem'>" +
                    "<strong>Demo accounts (password = 'password'):</strong><br>" +
                    "alice@yep.ug · bob@yep.ug · grace@yep.ug · david@yep.ug · admin@yep.ug" +
                "</div>" +
            "</div>";
        sendHtml(ex, 200, page("Login", null, content));
    }

    static void loginPost(HttpExchange ex) throws IOException {
        Map<String, String> f = parseForm(ex);
        try {
            User u = AUTH.login(f.get("email"), f.get("password"));
            String sid = UUID.randomUUID().toString();
            SESSIONS.put(sid, u);
            setSessionCookie(ex, sid);
            redirect(ex, "/dashboard");
        } catch (Exception e) {
            loginGet(ex, e.getMessage());
        }
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
            actHtml.append("<p class='muted'>You haven't enrolled in any activity yet.</p>")
                   .append("<a class='btn primary' href='/activities'>Browse activities</a>");
        } else {
            actHtml.append("<ul class='list'>");
            for (LearningActivity a : myActs) {
                actHtml.append("<li><strong>").append(esc(a.getTitle())).append("</strong>")
                       .append("<span class='badge'>Level ").append(a.getLevel().getValue()).append("</span>")
                       .append(" <span class='muted'>· ").append(esc(a.getCategory())).append("</span></li>");
            }
            actHtml.append("</ul>");
        }

        StringBuilder teamHtml = new StringBuilder();
        if (myTeam.isEmpty()) {
            teamHtml.append("<p class='muted'>Not in a team yet.</p>")
                    .append("<a class='btn' href='/level-two'>Go to Level Two</a>");
        } else {
            Team t = myTeam.get();
            teamHtml.append("<p><strong>").append(esc(t.getTeamName())).append("</strong>")
                     .append(" <span class='badge'>").append(t.getStatus()).append("</span></p>");
            if (t.getMentorId() != null) {
                USERS.findById(t.getMentorId()).ifPresent(m ->
                    teamHtml.append("<p class='muted'>Mentor: ").append(esc(m.getName())).append("</p>"));
            }
            teamHtml.append("<ul class='list'>");
            for (TeamMember m : t.getMembers())
                teamHtml.append("<li>").append(esc(m.getUserName()))
                        .append(" <span class='muted'>(").append(m.getRoleInTeam()).append(")</span></li>");
            teamHtml.append("</ul>");
            Optional<ImpactProject> proj = PROJECTS.findAll().stream()
                    .filter(p -> p.getTeamId().equals(t.getId())).findFirst();
            proj.ifPresent(p -> teamHtml.append("<p><strong>Project: ")
                    .append(esc(p.getTitle())).append("</strong> <span class='badge'>")
                    .append(p.getStatus()).append("</span></p>"));
        }

        StringBuilder discHtml = new StringBuilder();
        if (recentThreads.isEmpty()) {
            discHtml.append("<p class='muted'>No threads yet.</p>");
        } else {
            discHtml.append("<ul class='list'>");
            for (DiscussionThread t : recentThreads.subList(0, Math.min(5, recentThreads.size())))
                discHtml.append("<li><a href='/thread?id=").append(t.getId()).append("'>")
                         .append(esc(t.getTitle())).append("</a>")
                         .append(" <span class='muted'>— ").append(esc(t.getAuthorName()))
                         .append(" · ").append(t.getReplyCount()).append(" replies</span></li>");
            discHtml.append("</ul>");
        }

        StringBuilder certHtml = new StringBuilder();
        if (certs.isEmpty()) {
            certHtml.append("<p class='muted'>No certificates yet.</p>");
        } else {
            certHtml.append("<ul class='list'>");
            for (Certificate c : certs)
                certHtml.append("<li><strong>").append(esc(c.getTitle())).append("</strong>")
                         .append(" <span class='muted'>#").append(c.getCertificateNumber()).append("</span></li>");
            certHtml.append("</ul>");
        }

        String content =
            "<h1>Welcome, " + esc(user.getName()) + "</h1>" +
            "<p class='muted'>" + esc(user.getDashboardSummary()) + "</p>" +
            carousel() +
            "<div class='grid'>" +
                "<div class='card'><h2>📚 My Activities</h2>" + actHtml + "</div>" +
                "<div class='card'><h2>👥 My Team</h2>" + teamHtml + "</div>" +
            "</div>" +
            "<div class='card'><h2>💬 Recent Discussions</h2>" + discHtml +
                "<a class='btn' href='/discussion'>All discussions</a></div>" +
            "<div class='card'><h2>🏅 My Certificates</h2>" + certHtml +
                "<a class='btn' href='/certificate'>Certificate center</a></div>";

        sendHtml(ex, 200, page("Dashboard", user, content));
    }

    static void activities(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        String cat = q.getOrDefault("category", "");
        String lvl = q.getOrDefault("level", "");

        List<LearningActivity> all = ACTIVITIES.findAll().stream()
                .filter(LearningActivity::isPublished).collect(Collectors.toList());

        List<LearningActivity> filtered = all.stream()
                .filter(a -> cat.isEmpty() || cat.equals(a.getCategory()))
                .filter(a -> lvl.isEmpty() || Integer.parseInt(lvl) == a.getLevel().getValue())
                .collect(Collectors.toList());

        Set<String> cats = all.stream().map(LearningActivity::getCategory)
                .filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));

        StringBuilder catOpts = new StringBuilder("<option value=''>All categories</option>");
        for (String c : cats)
            catOpts.append("<option value='").append(esc(c)).append("'")
                   .append(c.equals(cat) ? " selected" : "").append(">")
                   .append(esc(c)).append("</option>");

        StringBuilder list = new StringBuilder();
        if (filtered.isEmpty()) {
            list.append("<div class='card'><p class='muted'>No activities match your filters.</p></div>");
        } else {
            for (LearningActivity a : filtered) {
                list.append("<div class='card'>")
                    .append("<h2>").append(esc(a.getTitle()))
                    .append(" <span class='badge'>Level ").append(a.getLevel().getValue()).append("</span></h2>")
                    .append("<p>").append(esc(a.getDescription())).append("</p>")
                    .append("<p class='muted'>").append(a.getType()).append(" · ")
                    .append(esc(a.getCategory())).append(" · ")
                    .append(fmtDate(a.getStartDate())).append(" → ").append(fmtDate(a.getEndDate()))
                    .append("</p>");
                if (a.getLevel().getValue() == 1) {
                    boolean enrolled = ENROLLS.findAll().stream().anyMatch(e ->
                        e.getUserId().equals(user.getId()) && e.getActivityId().equals(a.getId()));
                    if (enrolled) {
                        list.append("<span class='badge'>Enrolled ✓</span>");
                    } else {
                        list.append("<form method='post' action='/enroll' style='display:inline'>")
                            .append("<input type='hidden' name='activityId' value='").append(a.getId()).append("'>")
                            .append("<button class='btn primary' type='submit'>Enroll (Level 1)</button></form>");
                    }
                } else {
                    list.append("<a class='btn' href='/level-two'>Go to Level Two</a>");
                }
                list.append("</div>");
            }
        }

        String content =
            "<h1>Learning Activities</h1>" +
            "<form method='get' action='/activities' class='filters'>" +
                "<label>Category</label><select name='category' onchange='this.form.submit()'>" + catOpts + "</select>" +
                "<label>Level</label><select name='level' onchange='this.form.submit()'>" +
                    "<option value=''>All levels</option>" +
                    "<option value='1'" + ("1".equals(lvl) ? " selected" : "") + ">Level One</option>" +
                    "<option value='2'" + ("2".equals(lvl) ? " selected" : "") + ">Level Two</option>" +
                "</select>" +
            "</form>" + list;

        sendHtml(ex, 200, page("Activities", user, content));
    }

    static void enroll(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> f = parseForm(ex);
        try {
            Long aid = Long.parseLong(f.get("activityId"));
            LearningActivity a = ACTIVITIES.findById(aid).orElseThrow(
                    () -> new ValidationException("Activity not found"));
            LEARNING.enroll(user, a);
            NOTIFY.notify("INAPP", user, "Enrolled", "You enrolled in " + a.getTitle());
            redirect(ex, "/dashboard");
        } catch (Exception e) {
            redirect(ex, "/activities");
        }
    }

    static void levelOne(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        List<LearningActivity> list = LEARNING.byLevel(1);

        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Level One — Virtual Exchange</h1>")
          .append("<p class='muted'>Large-group online sessions covering digital literacy, AI, critical thinking, ")
          .append("media literacy, and intercultural communication.</p>");

        sb.append("<div class='card'><h2>Available Sessions</h2>");
        if (list.isEmpty()) {
            sb.append("<p class='muted'>No Level One sessions yet.</p>");
        } else {
            for (LearningActivity a : list) {
                sb.append("<div style='display:flex;justify-content:space-between;align-items:center;gap:12px;padding:10px 0;border-bottom:1px dashed #dfe5df'>")
                  .append("<div><strong>").append(esc(a.getTitle())).append("</strong>")
                  .append("<p class='muted' style='margin:4px 0'>").append(a.getType())
                  .append(" · ").append(esc(a.getCategory())).append("</p></div>");
                boolean enrolled = ENROLLS.findAll().stream().anyMatch(e ->
                    e.getUserId().equals(user.getId()) && e.getActivityId().equals(a.getId()));
                if (enrolled) {
                    sb.append("<span class='badge'>Enrolled ✓</span>");
                } else {
                    sb.append("<form method='post' action='/enroll'>")
                      .append("<input type='hidden' name='activityId' value='").append(a.getId()).append("'>")
                      .append("<button class='btn primary'>Join</button></form>");
                }
                sb.append("</div>");
            }
        }
        sb.append("</div>");

        sb.append("<div class='card'><h2>Open Discussions</h2>");
        List<DiscussionThread> threads = DISCUSS.all();
        if (threads.isEmpty()) {
            sb.append("<p class='muted'>Start the first discussion.</p>");
        } else {
            sb.append("<ul class='list'>");
            for (DiscussionThread t : threads)
                sb.append("<li><a href='/thread?id=").append(t.getId()).append("'>")
                  .append(esc(t.getTitle())).append("</a> <span class='muted'>— ")
                  .append(esc(t.getAuthorName())).append(" · ")
                  .append(t.getReplyCount()).append(" replies</span></li>");
            sb.append("</ul>");
        }
        sb.append("<a class='btn' href='/discussion'>Open Discussion Board</a></div>");

        sendHtml(ex, 200, page("Level One", user, sb.toString()));
    }

    static void levelTwo(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        List<LearningActivity> list = LEARNING.byLevel(2);
        Optional<Team> myTeam = TEAM_SVC.findByMember(user);

        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Level Two — Team Projects</h1>")
          .append("<p class='muted'>Selected participants form teams of 3–5 with a mentor to build impact projects ")
          .append("in employment, education, agriculture, health, climate change, or digital inclusion.</p>");

        sb.append("<div class='card'><h2>Level Two Exchanges</h2>");
        if (list.isEmpty()) {
            sb.append("<p class='muted'>No Level Two exchanges yet.</p>");
        } else {
            for (LearningActivity a : list) {
                sb.append("<div style='padding:10px 0;border-bottom:1px dashed #dfe5df'>")
                  .append("<strong>").append(esc(a.getTitle())).append("</strong>")
                  .append("<p class='muted' style='margin:4px 0'>").append(esc(a.getCategory()))
                  .append(" · ").append(fmtDate(a.getStartDate())).append(" → ")
                  .append(fmtDate(a.getEndDate())).append("</p></div>");
            }
        }
        sb.append("</div>");

        if (myTeam.isEmpty()) {
            sb.append("<div class='card'><h2>Create your team</h2>");
            if (user.isEligibleForLevelTwo()) {
                sb.append("<p class='muted'>You are eligible. Name your team and pick an exchange.</p>");
                sb.append("<form method='post' action='/team'>")
                  .append("<label>Team name</label><input name='teamName' required>")
                  .append("<label>Choose exchange</label><select name='activityId'>");
                for (LearningActivity a : list)
                    sb.append("<option value='").append(a.getId()).append("'>")
                      .append(esc(a.getTitle())).append("</option>");
                sb.append("</select>")
                  .append("<button class='btn primary' type='submit' style='margin-top:14px'>Create team</button>")
                  .append("</form>");
            } else {
                sb.append("<p class='muted'>Level Two is by invitation. Complete Level One and ")
                  .append("your facilitator will invite you.</p>");
            }
            sb.append("</div>");
        } else {
            Team t = myTeam.get();
            sb.append("<div class='card'><h2>").append(esc(t.getTeamName()))
              .append(" <span class='badge'>").append(t.getStatus()).append("</span></h2>")
              .append("<ul class='list'>");
            for (TeamMember m : t.getMembers())
                sb.append("<li>").append(esc(m.getUserName())).append(" <span class='muted'>(")
                  .append(m.getRoleInTeam()).append(")</span></li>");
            sb.append("</ul>");
            if (t.getMentorId() != null) {
                USERS.findById(t.getMentorId()).ifPresent(m ->
                    sb.append("<p class='muted'>Mentor: ").append(esc(m.getName())).append("</p>"));
            }
            sb.append("</div>");
        }

        sendHtml(ex, 200, page("Level Two", user, sb.toString()));
    }

    static void teamPost(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> f = parseForm(ex);
        try {
            Long actId = Long.parseLong(f.get("activityId"));
            TEAM_SVC.createTeam(f.get("teamName"), actId, user);
            NOTIFY.notify("INAPP", user, "Team created", "Welcome to " + f.get("teamName"));
            redirect(ex, "/level-two");
        } catch (Exception e) {
            redirect(ex, "/level-two");
        }
    }

    static void discussionGet(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        String cat = q.getOrDefault("category", "");
        List<DiscussionThread> threads = DISCUSS.byCategory(cat.isEmpty() ? null : cat);

        String[] cats = {"AI Ethics","Media Literacy","Intercultural Communication",
                         "Digital Skills","Agriculture & Climate","Health",
                         "Employment & Entrepreneurship","Team Collaboration"};
        StringBuilder catOpts = new StringBuilder();
        for (String c : cats) catOpts.append("<option>").append(esc(c)).append("</option>");

        StringBuilder filterOpts = new StringBuilder("<option value=''>All categories</option>");
        for (String c : cats)
            filterOpts.append("<option ").append(c.equals(cat) ? "selected" : "").append(">")
                       .append(esc(c)).append("</option>");

        StringBuilder threadsHtml = new StringBuilder();
        if (threads.isEmpty()) {
            threadsHtml.append("<p class='muted'>No threads yet — be the first!</p>");
        } else {
            threadsHtml.append("<ul class='list'>");
            for (DiscussionThread t : threads)
                threadsHtml.append("<li><a href='/thread?id=").append(t.getId()).append("'><strong>")
                    .append(esc(t.getTitle())).append("</strong></a>")
                    .append(" <span class='badge'>").append(esc(t.getCategory())).append("</span>")
                    .append("<p class='muted' style='margin:4px 0'>")
                    .append(esc(t.getAuthorName())).append(" · ")
                    .append(t.getReplyCount()).append(" replies</p></li>");
            threadsHtml.append("</ul>");
        }

        String content =
            "<h1>Discussion Board</h1>" +
            "<div class='card'><h2>Start a new discussion</h2>" +
                "<form method='post' action='/discussion'>" +
                    "<label>Category</label><select name='category' required>" +
                        "<option value=''>Select a topic…</option>" + catOpts +
                    "</select>" +
                    "<label>Title</label><input name='title' required>" +
                    "<label>Message</label><textarea name='body' rows='4' required></textarea>" +
                    "<button class='btn primary' type='submit' style='margin-top:14px'>Post discussion</button>" +
                "</form>" +
            "</div>" +
            "<div class='card'><h2>All Threads</h2>" +
                "<form method='get' action='/discussion' style='margin-bottom:14px'>" +
                    "<label>Filter by category</label>" +
                    "<select name='category' onchange='this.form.submit()'>" + filterOpts + "</select>" +
                "</form>" + threadsHtml +
            "</div>";

        sendHtml(ex, 200, page("Discussions", user, content));
    }

    static void discussionPost(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> f = parseForm(ex);
        try {
            DISCUSS.createThread(user, null, f.get("category"), f.get("title"), f.get("body"));
            redirect(ex, "/discussion");
        } catch (Exception e) {
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
        if (t.getPosts().isEmpty()) {
            replies.append("<p class='muted'>No replies yet.</p>");
        } else {
            for (DiscussionPost p : t.getPosts())
                replies.append("<div class='card'><p><strong>").append(esc(p.getAuthorName()))
                    .append("</strong></p><p>").append(esc(p.getBody())).append("</p></div>");
        }

        String content =
            "<a href='/discussion' class='muted'>← Back to discussions</a>" +
            "<h1>" + esc(t.getTitle()) + "</h1>" +
            "<p class='muted'>" + esc(t.getAuthorName()) + " · " + esc(t.getCategory()) + "</p>" +
            "<div class='card'><p>" + esc(t.getBody()) + "</p></div>" +
            "<h2>Replies (" + t.getReplyCount() + ")</h2>" + replies +
            "<div class='card'><h3>Write a reply</h3>" +
                "<form method='post' action='/thread?id=" + t.getId() + "'>" +
                    "<textarea name='body' rows='4' required></textarea>" +
                    "<button class='btn primary' type='submit' style='margin-top:14px'>Reply</button>" +
                "</form>" +
            "</div>";

        sendHtml(ex, 200, page(t.getTitle(), user, content));
    }

    static void threadPost(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        Map<String, String> f = parseForm(ex);
        Long id = Long.valueOf(q.getOrDefault("id", "0"));
        try {
            THREADS.findById(id).ifPresent(t -> DISCUSS.reply(t, user, f.get("body")));
        } catch (Exception ignored) {}
        redirect(ex, "/thread?id=" + id);
    }

    static void certificate(HttpExchange ex, User user) throws IOException {
        if (user == null) { redirect(ex, "/login"); return; }
        List<Certificate> certs = CERTS.findAll().stream()
                .filter(c -> c.getUserId().equals(user.getId())).collect(Collectors.toList());

        StringBuilder html = new StringBuilder();
        if (certs.isEmpty()) {
            html.append("<div class='card'><p class='muted'>No certificates yet. Complete a Level 1 quiz ")
                .append("or a Level 2 project to earn one.</p></div>");
        } else {
            for (Certificate c : certs)
                html.append("<div class='card' style='border-left:6px solid #f4c430'>")
                    .append("<h2>").append(esc(c.getTitle())).append("</h2>")
                    .append("<p>Awarded to <strong>").append(esc(c.getParticipantName())).append("</strong></p>")
                    .append("<p class='muted'>Certificate #").append(c.getCertificateNumber())
                    .append(" · ").append(fmtDate(c.getIssueDate().toLocalDate())).append("</p>")
                    .append("<p class='muted'>Verify at: ").append(esc(c.getQrCodeUrl())).append("</p>")
                    .append("</div>");
        }

        String content =
            "<h1>My Certificates</h1>" + html +
            "<div class='card'><h2>Verify a certificate</h2>" +
                "<form method='post' action='/certificate'>" +
                    "<label>Certificate number</label><input name='number' required>" +
                    "<button class='btn primary' type='submit' style='margin-top:14px'>Verify</button>" +
                "</form>" +
            "</div>";

        sendHtml(ex, 200, page("Certificates", user, content));
    }

    // ==================== HTML HELPERS ====================

    static final String CSS = """
        *{box-sizing:border-box}
        body{margin:0;font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;background:#f5f7f5;color:#1c241c;line-height:1.55}
        .topbar{background:#064a27;color:#fff;padding:14px 24px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px}
        .topbar .brand{font-weight:700;font-size:1.05rem}
        .topbar nav{display:flex;gap:16px;flex-wrap:wrap}
        .topbar a{color:#fff;text-decoration:none;font-size:.92rem;opacity:.9}
        .topbar a:hover{opacity:1;text-decoration:underline}
        .container{max-width:1100px;margin:24px auto;padding:0 20px}
        .btn{display:inline-block;padding:10px 18px;border-radius:8px;border:1px solid #dfe5df;background:#fff;color:#1c241c;text-decoration:none;cursor:pointer;font-size:.92rem;font-family:inherit}
        .btn:hover{transform:translateY(-1px)}
        .btn.primary{background:#0a6e3a;border-color:#0a6e3a;color:#fff}
        .btn.primary:hover{background:#064a27}
        .card{background:#fff;border:1px solid #dfe5df;border-radius:12px;padding:22px;margin:16px 0}
        h1,h2,h3{color:#064a27;margin:0 0 12px}
        h1{font-size:1.7rem}
        .muted{color:#6b776b;font-size:.9rem}
        label{display:block;margin:10px 0 6px;font-size:.88rem;color:#6b776b}
        input,select,textarea{width:100%;padding:10px 12px;border:1px solid #dfe5df;border-radius:8px;font-size:.95rem;font-family:inherit;background:#fff}
        input:focus,select:focus,textarea:focus{outline:2px solid #f4c430}
        .grid{display:grid;gap:16px;grid-template-columns:repeat(auto-fit,minmax(280px,1fr))}
        .list{list-style:none;padding:0;margin:0 0 12px}
        .list li{padding:10px 0;border-bottom:1px dashed #dfe5df}
        .list li:last-child{border-bottom:0}
        .badge{display:inline-block;padding:2px 8px;border-radius:999px;background:#e5f4ea;color:#064a27;font-size:.75rem;margin-left:6px}
        .alert{padding:12px 14px;border-radius:8px;margin:12px 0}
        .alert.error{background:#fdecea;border:1px solid #f5c6c2;color:#c0392b}
        .carousel{position:relative;height:320px;border-radius:14px;overflow:hidden;margin-bottom:24px;background:#064a27;box-shadow:0 4px 12px rgba(0,0,0,.08)}
        .slide{position:absolute;inset:0;opacity:0;transition:opacity .8s;display:flex;align-items:center;justify-content:center;text-align:center;padding:40px}
        .slide.active{opacity:1}
        .slide-content{color:#fff;max-width:600px}
        .slide-icon{font-size:64px;margin-bottom:14px}
        .slide h2{color:#fff;font-size:1.9rem;margin:0 0 10px}
        .slide p{opacity:.92;margin:0;font-size:1.02rem}
        .carousel .dots{position:absolute;bottom:14px;left:50%;transform:translateX(-50%);display:flex;gap:8px}
        .carousel .dots span{width:10px;height:10px;border-radius:50%;background:rgba(255,255,255,.5);cursor:pointer}
        .carousel .dots span.active{background:#fff}
        .footer{text-align:center;padding:28px;color:#6b776b;font-size:.85rem}
        .filters{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:16px}
        .filters label{flex:1;min-width:180px;margin:0}
        """;

    static String page(String title, User user, String content) {
        return "<!DOCTYPE html><html lang='en'><head>" +
                "<meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>" + esc(title) + " — YEP</title>" +
                "<style>" + CSS + "</style>" +
                "</head><body>" +
                nav(user) +
                "<main class='container'>" + content + "</main>" +
                "<footer class='footer'>© 2026 YEP — Youth Empowerment Project</footer>" +
                "<script>" + CAROUSEL_JS + "</script>" +
                "</body></html>";
    }

    static String nav(User user) {
        StringBuilder sb = new StringBuilder();
        sb.append("<header class='topbar'><span class='brand'>YEP · Youth Empowerment Project</span><nav>");
        if (user == null) {
            sb.append("<a href='/'>Home</a><a href='/login'>Sign in</a><a href='/register'>Join YEP</a>");
        } else {
            sb.append("<a href='/dashboard'>Dashboard</a>")
              .append("<a href='/activities'>Activities</a>")
              .append("<a href='/level-one'>Level One</a>")
              .append("<a href='/level-two'>Level Two</a>")
              .append("<a href='/discussion'>Discussions</a>")
              .append("<a href='/certificate'>Certificates</a>")
              .append("<a href='/logout'>Logout</a>");
        }
        sb.append("</nav></header>");
        return sb.toString();
    }

    static String carousel() {
        return "<div class='carousel' id='c'>" +
                slide("🎓", "Level One — Learn Together",
                        "Large-group virtual exchanges on AI, media literacy, and intercultural communication.",
                        "linear-gradient(135deg,#0a6e3a,#064a27)", true) +
                slide("🌍", "Level Two — Build Together",
                        "Selected teams design impact projects with a mentor, across borders.",
                        "linear-gradient(135deg,#1565c0,#0d47a1)", false) +
                slide("💬", "Discuss & Exchange",
                        "Peer discussion, mentor feedback and cross-border dialogue.",
                        "linear-gradient(135deg,#8e24aa,#4a148c)", false) +
                slide("🏅", "Recognised Achievements",
                        "Verifiable digital certificates that employers and universities can trust.",
                        "linear-gradient(135deg,#ef6c00,#e65100)", false) +
                "<div class='dots' id='dots'></div>" +
                "</div>";
    }

    static String slide(String icon, String title, String text, String gradient, boolean active) {
        return "<div class='slide" + (active ? " active" : "") + "' style='background:" + gradient + "'>" +
                "<div class='slide-content'>" +
                "<div class='slide-icon'>" + icon + "</div>" +
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
          setInterval(function(){ go(i + 1); }, 4500);
        })();
        """;

    // ==================== REQUEST HELPERS ====================

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
        for (String h : headers) {
            for (String c : h.split(";")) {
                c = c.trim();
                if (c.startsWith(name + "=")) return c.substring(name.length() + 1);
            }
        }
        return null;
    }

    static void setSessionCookie(HttpExchange ex, String sid) {
        ex.getResponseHeaders().add("Set-Cookie",
                "YEP_SESSION=" + sid + "; Path=/; HttpOnly; Max-Age=86400; SameSite=Lax");
    }

    static User currentUser(HttpExchange ex) {
        String sid = readCookie(ex, "YEP_SESSION");
        return sid == null ? null : SESSIONS.get(sid);
    }

    static Map<String, String> parseForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseQuery(body);
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return map;
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) map.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                else map.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        return map;
    }

    static Integer parseIntOrNull(String s) {
        try { return s == null || s.isBlank() ? null : Integer.parseInt(s); } catch (Exception e) { return null; }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String fmtDate(LocalDate d) {
        return d == null ? "—" : DATE_FMT.format(d);
    }

    static String dropdownOptions(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) sb.append("<option>").append(esc(v)).append("</option>");
        return sb.toString();
    }

    // ==================== SEED DATA ====================

    static void seedData() {
        try {
            YouthParticipant alice = (YouthParticipant) AUTH.register(
                "YOUTH", "Alice Nakato", "alice@yep.ug", "+256770000001", "password",
                22, "Female", "Uganda", "Kampala", "BSc Computer Science", null, null);
            YouthParticipant bob = (YouthParticipant) AUTH.register(
                "YOUTH", "Bob Okello", "bob@yep.ug", "+256770000002", "password",
                26, "Male", "Uganda", "Gulu", "Diploma in Agriculture", null, null);
            YouthParticipant charlie = (YouthParticipant) AUTH.register(
                "YOUTH", "Charlie Mwangi", "charlie@yep.ke", "+254780000003", "password",
                24, "Male", "Kenya", "Nairobi", "BA Communications", null, null);
            Facilitator david = (Facilitator) AUTH.register(
                "FACILITATOR", "David Ssem", "david@yep.ug", "+256770000005", "password",
                null, null, null, null, null, "Digital Literacy", null);
            Mentor grace = (Mentor) AUTH.register(
                "MENTOR", "Grace Achieng", "grace@yep.ug", "+256770000004", "password",
                null, null, null, null, null, "AgriTech", "Makerere University");
            AUTH.register("ADMIN", "YEP Admin", "admin@yep.ug", "+256770000006", "password",
                null, null, null, null, null, null, null);

            LearningActivity ai101 = LEARNING.create(1, "AI Literacy Fundamentals",
                "Responsible AI, misinformation and critical thinking",
                ActivityType.SELF_PACED, "AI Literacy",
                LocalDate.now(), LocalDate.now().plusDays(14),
                "https://content.yep.ug/ai101", david.getId());
            LearningActivity story = LEARNING.create(1, "Digital Storytelling Workshop",
                "Video, audio and written storytelling for impact",
                ActivityType.WORKSHOP, "Media Literacy",
                LocalDate.now(), LocalDate.now().plusDays(21),
                "https://content.yep.ug/story", david.getId());
            LEARNING.create(1, "Intercultural Communication Live",
                "Live virtual exchange with peers in Kenya and Tanzania",
                ActivityType.LIVE_SESSION, "Intercultural Communication",
                LocalDate.now().plusDays(3), LocalDate.now().plusDays(3),
                "https://content.yep.ug/ic-live", david.getId());
            LearningActivity l2Agri = LEARNING.create(2, "Level Two: AgriTech Impact Teams",
                "Team-based impact project development in agriculture",
                ActivityType.VIRTUAL_EXCHANGE, "Agriculture",
                LocalDate.now(), LocalDate.now().plusDays(60),
                "https://content.yep.ug/l2-agri", david.getId());

            LEARNING.enroll(alice, ai101);
            LEARNING.enroll(bob, ai101);

            alice.setLevelTwoEligible(true);
            bob.setLevelTwoEligible(true);
            charlie.setLevelTwoEligible(true);

            Team agri = TEAM_SVC.createTeam("Agri Innovators", l2Agri.getId(), alice);
            TEAM_SVC.addMember(agri, bob);
            TEAM_SVC.addMember(agri, charlie);
            TEAM_SVC.assignMentor(agri, grace);

            ImpactProject proj = PROJ_SVC.create(agri, "Smart Agri Advisor",
                ChallengeArea.AGRICULTURE,
                "Mobile-first crop advisory service for smallholder farmers",
                LocalDate.now(), LocalDate.now().plusMonths(3));
            PROJ_SVC.addMilestone(proj, "Problem statement", LocalDate.now().plusDays(7));
            PROJ_SVC.addMilestone(proj, "MVP mockup", LocalDate.now().plusDays(30));

            DiscussionThread t = DISCUSS.createThread(alice, ai101.getId(), "AI Ethics",
                "How do we spot deepfakes?",
                "I saw a video claiming to be a minister, but the lips did not match the audio. How can we verify?");
            DISCUSS.reply(t, bob,
                "Cross-check the source on the ministry's official site and look for the same clip on 2+ reputable outlets.");
            DISCUSS.reply(t, charlie,
                "Also check metadata using a tool like InVID and try a reverse image search.");

            Assessment quiz = new Assessment(ai101.getId(), "AI Fundamentals Quiz", 100);
            ASSESS.assessAndCertify(new QuizAssessmentStrategy(), quiz, alice, 85);
            ASSESS.assessAndCertify(new QuizAssessmentStrategy(), quiz, bob, 42);

            System.out.println("[seed] " + USERS.findAll().size() + " users, " +
                    ACTIVITIES.findAll().size() + " activities, " +
                    TEAMS.findAll().size() + " team(s), " +
                    THREADS.findAll().size() + " thread(s), " +
                    CERTS.findAll().size() + " certificate(s)");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

// ============================================================================
// ENUMS
// ============================================================================

enum UserRole {
    YOUTH("Youth Participant"), FACILITATOR("Facilitator"), MENTOR("Mentor"),
    ADMIN("Administrator"), PARTNER("Partner Organisation");
    private final String label;
    UserRole(String l) { this.label = l; }
    public String getLabel() { return label; }
}
enum AccountStatus { PENDING, ACTIVE, SUSPENDED }
enum ActivityType { LIVE_SESSION, SELF_PACED, WORKSHOP, VIRTUAL_EXCHANGE }
enum ActivityLevel {
    LEVEL_ONE(1, "Level One — Large-group"), LEVEL_TWO(2, "Level Two — Team projects");
    private final int value; private final String desc;
    ActivityLevel(int v, String d) { this.value = v; this.desc = d; }
    public int getValue() { return value; }
    public String getDescription() { return desc; }
    public static ActivityLevel fromInt(int v) { return v == 1 ? LEVEL_ONE : LEVEL_TWO; }
}
enum ChallengeArea {
    EMPLOYMENT("Employment"), EDUCATION("Education"), AGRICULTURE("Agriculture"),
    HEALTH("Health"), CLIMATE_CHANGE("Climate Change"), DIGITAL_INCLUSION("Digital Inclusion");
    private final String label;
    ChallengeArea(String l) { this.label = l; }
    public String getLabel() { return label; }
}
enum ProjectStatus { DRAFT, IN_PROGRESS, SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED }
enum EnrollmentStatus { IN_PROGRESS, COMPLETED, DROPPED }
enum NotificationType { EMAIL, SMS, IN_APP }

// ============================================================================
// EXCEPTIONS
// ============================================================================

class YepException extends RuntimeException {
    public YepException(String m) { super(m); }
    public YepException(String m, Throwable c) { super(m, c); }
}
class ValidationException extends YepException {
    public ValidationException(String m) { super(m); }
}
class AuthenticationException extends YepException {
    public AuthenticationException(String m) { super(m); }
}

// ============================================================================
// UTILITIES
// ============================================================================

final class ValidationUtil {
    private ValidationUtil() {}
    static boolean isBlank(String s)    { return s == null || s.trim().isEmpty(); }
    static boolean isEmail(String s)    { return s != null && s.matches("^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$"); }
    static boolean isPhone(String s)    { return s != null && s.matches("^\\+?[0-9]{7,15}$"); }
    static boolean minLength(String s, int n) { return s != null && s.length() >= n; }
    static boolean isAge18to35(Integer a)     { return a != null && a >= 18 && a <= 35; }
}

final class PasswordUtil {
    private PasswordUtil() {}
    static String generateSalt() {
        byte[] s = new byte[16]; new SecureRandom().nextBytes(s);
        return Base64.getEncoder().encodeToString(s);
    }
    static String hash(String pwd, String saltB64) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Base64.getDecoder().decode(saltB64));
            return Base64.getEncoder().encodeToString(md.digest(pwd.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new YepException("Hashing failed", e); }
    }
    static boolean verify(String pwd, String saltB64, String expectedHash) {
        return hash(pwd, saltB64).equals(expectedHash);
    }
}

// ============================================================================
// BASE ENTITY
// ============================================================================

abstract class BaseEntity {
    private static long SEQ = 1;
    private final Long id = SEQ++;
    private final LocalDateTime createdAt = LocalDateTime.now();
    public Long getId()                 { return id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public abstract String displayLabel();
    @Override public String toString() { return displayLabel(); }
}

// ============================================================================
// USER HIERARCHY
// ============================================================================

class Profile {
    private String bio, photoUrl, languagePreference = "en";
    public String getBio() { return bio; }
    public void setBio(String v) { this.bio = v; }
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String v) { this.photoUrl = v; }
    public String getLanguagePreference() { return languagePreference; }
    public void setLanguagePreference(String v) { this.languagePreference = v; }
}

abstract class User extends BaseEntity {
    private String name, email, phone, passwordHash, passwordSalt;
    private AccountStatus status = AccountStatus.PENDING;
    private final Profile profile = new Profile();

    protected User(String name, String email, String phone) {
        setName(name); setEmail(email); this.phone = phone;
    }

    public abstract UserRole getRole();
    public abstract boolean isEligibleForLevelTwo();
    public abstract String getDashboardSummary();

    public void activate()    { this.status = AccountStatus.ACTIVE; }
    public void suspend()     { this.status = AccountStatus.SUSPENDED; }
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
    private Integer age;
    private String gender, country, district, educationLevel;
    private boolean levelTwoEligible;
    public YouthParticipant(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole() { return UserRole.YOUTH; }
    @Override public boolean isEligibleForLevelTwo() {
        return isActive() && levelTwoEligible && age != null && age >= 18 && age <= 35;
    }
    @Override public String getDashboardSummary() {
        return String.format("Youth · %s · Level-2 eligible: %s",
                country == null ? "—" : country, levelTwoEligible ? "YES" : "no");
    }
    public Integer getAge() { return age; }
    public void setAge(Integer v) { if (v != null && (v < 18 || v > 35)) throw new ValidationException("Age must be 18–35"); this.age = v; }
    public String getGender() { return gender; }
    public void setGender(String v) { this.gender = v; }
    public String getCountry() { return country; }
    public void setCountry(String v) { this.country = v; }
    public String getDistrict() { return district; }
    public void setDistrict(String v) { this.district = v; }
    public String getEducationLevel() { return educationLevel; }
    public void setEducationLevel(String v) { this.educationLevel = v; }
    public boolean isLevelTwoEligible() { return levelTwoEligible; }
    public void setLevelTwoEligible(boolean v) { this.levelTwoEligible = v; }
}

class Facilitator extends User {
    private String expertise;
    public Facilitator(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole() { return UserRole.FACILITATOR; }
    @Override public boolean isEligibleForLevelTwo() { return false; }
    @Override public String getDashboardSummary() { return "Facilitator · " + getName() + (expertise != null ? " · " + expertise : ""); }
    public String getExpertise() { return expertise; }
    public void setExpertise(String v) { this.expertise = v; }
}

class Mentor extends User {
    private String expertise, organisation;
    public Mentor(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole() { return UserRole.MENTOR; }
    @Override public boolean isEligibleForLevelTwo() { return false; }
    @Override public String getDashboardSummary() { return "Mentor · " + getName() + (organisation != null ? " @ " + organisation : ""); }
    public String getExpertise() { return expertise; }
    public void setExpertise(String v) { this.expertise = v; }
    public String getOrganisation() { return organisation; }
    public void setOrganisation(String v) { this.organisation = v; }
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
    public String getOrgType() { return orgType; }
    public void setOrgType(String v) { this.orgType = v; }
}

// ============================================================================
// ACTIVITY HIERARCHY
// ============================================================================

abstract class LearningActivity extends BaseEntity {
    private String title, description, category, contentUrl;
    private ActivityType type;
    private LocalDate startDate, endDate;
    private boolean published;
    private Long facilitatorId;
    private int maxParticipants = 100;

    protected LearningActivity(String title, String desc, ActivityType type,
                               String cat, LocalDate start, LocalDate end, String url) {
        if (ValidationUtil.isBlank(title)) throw new ValidationException("Title required");
        if (start != null && end != null && end.isBefore(start))
            throw new ValidationException("End date must follow start date");
        this.title = title.trim();
        this.description = desc;
        this.type = type;
        this.category = cat;
        this.startDate = start;
        this.endDate = end;
        this.contentUrl = url;
    }

    public abstract ActivityLevel getLevel();
    public abstract List<String> getLevelFeatures();
    public abstract boolean supportsTeamFormation();

    public void publish() { this.published = true; }
    @Override public String displayLabel() { return "[L" + getLevel().getValue() + "] " + title; }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public ActivityType getType() { return type; }
    public String getCategory() { return category; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getContentUrl() { return contentUrl; }
    public boolean isPublished() { return published; }
    public Long getFacilitatorId() { return facilitatorId; }
    public void setFacilitatorId(Long v) { this.facilitatorId = v; }
    public int getMaxParticipants() { return maxParticipants; }
}

class LevelOneActivity extends LearningActivity {
    public LevelOneActivity(String t, String d, ActivityType ty, String c,
                            LocalDate s, LocalDate e, String u) {
        super(t, d, ty, c, s, e, u);
    }
    @Override public ActivityLevel getLevel() { return ActivityLevel.LEVEL_ONE; }
    @Override public boolean supportsTeamFormation() { return false; }
    @Override public List<String> getLevelFeatures() {
        return List.of(
            "Large-group online sessions",
            "Digital literacy & AI fundamentals",
            "Media literacy & critical thinking",
            "Intercultural communication",
            "Open discussion forum"
        );
    }
}

class LevelTwoActivity extends LearningActivity {
    private int maxTeams = 10, minTeamSize = 3, maxTeamSize = 5;
    public LevelTwoActivity(String t, String d, ActivityType ty, String c,
                            LocalDate s, LocalDate e, String u) {
        super(t, d, ty, c, s, e, u);
    }
    @Override public ActivityLevel getLevel() { return ActivityLevel.LEVEL_TWO; }
    @Override public boolean supportsTeamFormation() { return true; }
    @Override public List<String> getLevelFeatures() {
        return List.of(
            "Selected participants only",
            "Team formation (3–5 members)",
            "Mentor assigned per team",
            "Impact-project development",
            "Milestones, submissions & assessment",
            "Cross-border virtual exchange"
        );
    }
    public int getMaxTeams() { return maxTeams; }
    public int getMinTeamSize() { return minTeamSize; }
    public int getMaxTeamSize() { return maxTeamSize; }
}

// ============================================================================
// SUPPORTING MODELS
// ============================================================================

class Enrollment extends BaseEntity {
    private Long userId, activityId;
    private double progress;
    private EnrollmentStatus status = EnrollmentStatus.IN_PROGRESS;
    public Enrollment(Long u, Long a) { this.userId = u; this.activityId = a; }
    public void updateProgress(double pct) {
        if (pct < 0 || pct > 100) throw new ValidationException("0–100 only");
        this.progress = pct;
        if (pct >= 100) this.status = EnrollmentStatus.COMPLETED;
    }
    @Override public String displayLabel() { return "Enrollment #" + getId(); }
    public Long getUserId() { return userId; }
    public Long getActivityId() { return activityId; }
    public double getProgress() { return progress; }
    public EnrollmentStatus getStatus() { return status; }
}

class Assessment extends BaseEntity {
    private Long activityId;
    private String title;
    private int maxScore = 100, passScore = 50;
    public Assessment(Long a, String t, int max) {
        this.activityId = a; this.title = t; this.maxScore = max;
        this.passScore = (int) Math.ceil(max * 0.5);
    }
    public boolean isPassed(double score) { return score >= passScore; }
    @Override public String displayLabel() { return "Assessment: " + title; }
    public Long getActivityId() { return activityId; }
    public String getTitle() { return title; }
    public int getMaxScore() { return maxScore; }
    public int getPassScore() { return passScore; }
}

class Certificate extends BaseEntity {
    private String certificateNumber;
    private Long userId, activityId, projectId;
    private String participantName, title, qrCodeUrl;
    private LocalDateTime issueDate = LocalDateTime.now();
    public Certificate(Long uid, String name, String title) {
        this.userId = uid; this.participantName = name; this.title = title;
        this.certificateNumber = "YEP-" + System.currentTimeMillis() + "-" + (1000 + new Random().nextInt(9000));
        this.qrCodeUrl = "https://verify.yep.ug/" + certificateNumber;
    }
    @Override public String displayLabel() { return "Certificate " + certificateNumber; }
    public String getCertificateNumber() { return certificateNumber; }
    public Long getUserId() { return userId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long v) { this.activityId = v; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long v) { this.projectId = v; }
    public String getParticipantName() { return participantName; }
    public String getTitle() { return title; }
    public String getQrCodeUrl() { return qrCodeUrl; }
    public LocalDateTime getIssueDate() { return issueDate; }
}

class TeamMember extends BaseEntity {
    private Long teamId, userId;
    private String roleInTeam = "MEMBER", userName;
    public TeamMember(Long team, Long user, String name, String role) {
        this.teamId = team; this.userId = user; this.userName = name; this.roleInTeam = role;
    }
    @Override public String displayLabel() { return userName + " (" + roleInTeam + ")"; }
    public Long getTeamId() { return teamId; }
    public Long getUserId() { return userId; }
    public String getRoleInTeam() { return roleInTeam; }
    public String getUserName() { return userName; }
}

class Team extends BaseEntity {
    private String teamName, status = "FORMING";
    private Long mentorId, levelTwoActivityId;
    private final List<TeamMember> members = new ArrayList<>();
    public Team(String name) {
        if (ValidationUtil.isBlank(name)) throw new ValidationException("Team name required");
        this.teamName = name.trim();
    }
    public void addMember(TeamMember m) { members.add(m); }
    @Override public String displayLabel() { return "Team: " + teamName; }
    public String getTeamName() { return teamName; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Long getMentorId() { return mentorId; }
    public void setMentorId(Long v) { this.mentorId = v; }
    public Long getLevelTwoActivityId() { return levelTwoActivityId; }
    public void setLevelTwoActivityId(Long v) { this.levelTwoActivityId = v; }
    public List<TeamMember> getMembers() { return Collections.unmodifiableList(members); }
}

class ImpactProject extends BaseEntity {
    private Long teamId;
    private String title, description;
    private ChallengeArea challengeArea;
    private ProjectStatus status = ProjectStatus.DRAFT;
    private LocalDate startDate, endDate;
    private final List<Milestone> milestones = new ArrayList<>();
    public ImpactProject(Long team, String title, ChallengeArea area, String desc) {
        if (ValidationUtil.isBlank(title)) throw new ValidationException("Title required");
        this.teamId = team;
        this.title = title.trim();
        this.challengeArea = area;
        this.description = desc;
    }
    public void start(LocalDate s, LocalDate e) {
        this.startDate = s; this.endDate = e;
        this.status = ProjectStatus.IN_PROGRESS;
    }
    public void addMilestone(Milestone m) { milestones.add(m); }
    @Override public String displayLabel() { return "Project: " + title; }
    public Long getTeamId() { return teamId; }
    public String getTitle() { return title; }
    public ChallengeArea getChallengeArea() { return challengeArea; }
    public String getDescription() { return description; }
    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus v) { this.status = v; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public List<Milestone> getMilestones() { return Collections.unmodifiableList(milestones); }
}

class Milestone extends BaseEntity {
    private Long projectId;
    private String title, description;
    private LocalDate dueDate;
    private boolean completed;
    public Milestone(Long project, String title, LocalDate due) {
        this.projectId = project; this.title = title; this.dueDate = due;
    }
    public void complete() { this.completed = true; }
    @Override public String displayLabel() { return "Milestone: " + title; }
    public Long getProjectId() { return projectId; }
    public String getTitle() { return title; }
    public LocalDate getDueDate() { return dueDate; }
    public boolean isCompleted() { return completed; }
}

class Submission extends BaseEntity {
    private Long projectId;
    private String title, fileUrl, notes, feedback;
    private Double score;
    public Submission(Long p, String t, String url, String notes) {
        this.projectId = p; this.title = t; this.fileUrl = url; this.notes = notes;
    }
    public void grade(double s, String f) { this.score = s; this.feedback = f; }
    @Override public String displayLabel() { return "Submission: " + title; }
    public Long getProjectId() { return projectId; }
    public String getTitle() { return title; }
    public String getFileUrl() { return fileUrl; }
    public Double getScore() { return score; }
    public String getFeedback() { return feedback; }
}

// ============================================================================
// DISCUSSION
// ============================================================================

class DiscussionPost extends BaseEntity {
    private Long threadId, authorId;
    private String authorName, body;
    public DiscussionPost(Long t, Long a, String name, String body) {
        this.threadId = t; this.authorId = a; this.authorName = name; this.body = body;
    }
    @Override public String displayLabel() { return authorName + ": " + body.substring(0, Math.min(40, body.length())); }
    public Long getThreadId() { return threadId; }
    public String getAuthorName() { return authorName; }
    public String getBody() { return body; }
}

class DiscussionThread extends BaseEntity {
    private Long activityId, authorId;
    private String authorName, category, title, body;
    private final List<DiscussionPost> posts = new ArrayList<>();
    public DiscussionThread(Long activity, Long author, String name,
                            String cat, String title, String body) {
        if (ValidationUtil.isBlank(title)) throw new ValidationException("Thread title required");
        if (ValidationUtil.isBlank(body))  throw new ValidationException("Thread body required");
        this.activityId = activity; this.authorId = author; this.authorName = name;
        this.category = cat; this.title = title.trim(); this.body = body.trim();
    }
    public void addPost(DiscussionPost p) { posts.add(p); }
    @Override public String displayLabel() { return "Thread: " + title; }
    public Long getActivityId() { return activityId; }
    public String getAuthorName() { return authorName; }
    public String getCategory() { return category; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public List<DiscussionPost> getPosts() { return Collections.unmodifiableList(posts); }
    public int getReplyCount() { return posts.size(); }
}

// ============================================================================
// NOTIFICATIONS
// ============================================================================

abstract class Notification extends BaseEntity {
    private Long recipientId;
    private String subject, message;
    protected Notification(Long r, String s, String m) { this.recipientId = r; this.subject = s; this.message = m; }
    public abstract NotificationType getType();
    public abstract void send();
    @Override public String displayLabel() { return "[" + getType() + "] " + subject; }
    public Long getRecipientId() { return recipientId; }
    public String getSubject() { return subject; }
    public String getMessage() { return message; }
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

// ============================================================================
// FACTORIES
// ============================================================================

final class UserFactory {
    private UserFactory() {}
    static User create(String roleName, String name, String email, String phone) {
        return switch (UserRole.valueOf(roleName)) {
            case YOUTH       -> new YouthParticipant(name, email, phone);
            case FACILITATOR -> new Facilitator(name, email, phone);
            case MENTOR      -> new Mentor(name, email, phone);
            case ADMIN       -> new Administrator(name, email, phone);
            case PARTNER     -> new PartnerOrganisation(name, email, phone);
        };
    }
}

final class ActivityFactory {
    private ActivityFactory() {}
    static LearningActivity create(int level, String t, String d, ActivityType ty,
                                   String cat, LocalDate s, LocalDate e, String url) {
        return switch (level) {
            case 1 -> new LevelOneActivity(t, d, ty, cat, s, e, url);
            case 2 -> new LevelTwoActivity(t, d, ty, cat, s, e, url);
            default -> throw new ValidationException("Level must be 1 or 2");
        };
    }
}

final class NotificationFactory {
    private NotificationFactory() {}
    static Notification create(String channel, Long recipient, String subject, String msg) {
        return switch (channel.toUpperCase()) {
            case "EMAIL" -> new EmailNotification(recipient, subject, msg);
            case "SMS"   -> new SmsNotification(recipient, subject, msg);
            default      -> new InAppNotification(recipient, subject, msg);
        };
    }
}

// ============================================================================
// STRATEGY PATTERN
// ============================================================================

interface AssessmentStrategy {
    double calculateScore(double rawScore);
    boolean isPassing(double finalScore);
    String getName();
}
class QuizAssessmentStrategy implements AssessmentStrategy {
    @Override public double calculateScore(double r) { return r; }
    @Override public boolean isPassing(double s) { return s >= 50; }
    @Override public String getName() { return "Quiz (50% to pass)"; }
}
class ProjectAssessmentStrategy implements AssessmentStrategy {
    @Override public double calculateScore(double r) { return r * 1.1; }
    @Override public boolean isPassing(double s) { return s >= 60; }
    @Override public String getName() { return "Project (60% to pass, +10% bonus)"; }
}
class PeerAssessmentStrategy implements AssessmentStrategy {
    @Override public double calculateScore(double r) { return r * 0.9; }
    @Override public boolean isPassing(double s) { return s >= 40; }
    @Override public String getName() { return "Peer (40% to pass, -10% penalty)"; }
}

// ============================================================================
// REPOSITORY PATTERN
// ============================================================================

interface Repository<T extends BaseEntity> {
    T save(T entity);
    Optional<T> findById(Long id);
    List<T> findAll();
    void delete(Long id);
}

class InMemoryRepository<T extends BaseEntity> implements Repository<T> {
    private final Map<Long, T> store = new LinkedHashMap<>();
    @Override public T save(T e) { store.put(e.getId(), e); return e; }
    @Override public Optional<T> findById(Long id) { return Optional.ofNullable(store.get(id)); }
    @Override public List<T> findAll() { return new ArrayList<>(store.values()); }
    @Override public void delete(Long id) { store.remove(id); }
}

// ============================================================================
// SERVICES
// ============================================================================

class AuthService {
    private final Repository<User> users;
    AuthService(Repository<User> users) { this.users = users; }

    User register(String role, String name, String email, String phone, String password,
                  Integer age, String gender, String country, String district,
                  String education, String expertise, String organisation) {
        if (ValidationUtil.isBlank(name))           throw new ValidationException("Name required");
        if (!ValidationUtil.isEmail(email))         throw new ValidationException("Invalid email");
        if (!ValidationUtil.minLength(password, 6)) throw new ValidationException("Password must be ≥ 6 chars");
        if (users.findAll().stream().anyMatch(u -> u.getEmail().equalsIgnoreCase(email)))
            throw new ValidationException("Email already registered");

        User u = UserFactory.create(role, name, email, phone);
        if (u instanceof YouthParticipant y) {
            if (!ValidationUtil.isAge18to35(age))
                throw new ValidationException("Youth must be aged 18–35");
            y.setAge(age); y.setGender(gender); y.setCountry(country);
            y.setDistrict(district); y.setEducationLevel(education);
        }
        if (u instanceof Mentor m)      { m.setExpertise(expertise); m.setOrganisation(organisation); }
        if (u instanceof Facilitator f) { f.setExpertise(expertise); }

        String salt = PasswordUtil.generateSalt();
        u.setPasswordSalt(salt);
        u.setPasswordHash(PasswordUtil.hash(password, salt));
        u.activate();
        return users.save(u);
    }

    User login(String email, String password) {
        return users.findAll().stream()
                .filter(u -> u.getEmail().equalsIgnoreCase(email))
                .filter(u -> PasswordUtil.verify(password, u.getPasswordSalt(), u.getPasswordHash()))
                .findFirst()
                .orElseThrow(() -> new AuthenticationException("Invalid email or password"));
    }
}

class LearningService {
    private final Repository<LearningActivity> activities;
    private final Repository<Enrollment> enrollments;
    LearningService(Repository<LearningActivity> a, Repository<Enrollment> e) {
        this.activities = a; this.enrollments = e;
    }
    LearningActivity create(int level, String title, String desc, ActivityType type,
                            String category, LocalDate start, LocalDate end,
                            String url, Long facilitatorId) {
        LearningActivity a = ActivityFactory.create(level, title, desc, type, category, start, end, url);
        a.setFacilitatorId(facilitatorId);
        a.publish();
        return activities.save(a);
    }
    List<LearningActivity> byLevel(int level) {
        return activities.findAll().stream()
                .filter(a -> a.getLevel().getValue() == level && a.isPublished())
                .collect(Collectors.toList());
    }
    List<LearningActivity> byCategory(String cat) {
        return activities.findAll().stream()
                .filter(a -> cat == null || cat.equals(a.getCategory()))
                .filter(LearningActivity::isPublished)
                .collect(Collectors.toList());
    }
    Enrollment enroll(User user, LearningActivity activity) {
        if (!activity.isPublished()) throw new ValidationException("Cannot enroll: not published");
        if (enrollments.findAll().stream().anyMatch(e ->
                e.getUserId().equals(user.getId()) && e.getActivityId().equals(activity.getId())))
            throw new ValidationException("Already enrolled");
        return enrollments.save(new Enrollment(user.getId(), activity.getId()));
    }
    List<LearningActivity> forUser(Long userId) {
        Set<Long> enrolled = enrollments.findAll().stream()
                .filter(e -> e.getUserId().equals(userId))
                .map(Enrollment::getActivityId).collect(Collectors.toSet());
        return activities.findAll().stream()
                .filter(a -> enrolled.contains(a.getId())).collect(Collectors.toList());
    }
}

class DiscussionService {
    private final Repository<DiscussionThread> threads;
    private final Repository<DiscussionPost> posts;
    DiscussionService(Repository<DiscussionThread> t, Repository<DiscussionPost> p) {
        this.threads = t; this.posts = p;
    }
    DiscussionThread createThread(User author, Long activityId, String category,
                                  String title, String body) {
        return threads.save(new DiscussionThread(activityId, author.getId(),
                author.getName(), category, title, body));
    }
    DiscussionPost reply(DiscussionThread thread, User author, String body) {
        if (ValidationUtil.isBlank(body)) throw new ValidationException("Reply required");
        DiscussionPost p = posts.save(new DiscussionPost(thread.getId(), author.getId(),
                author.getName(), body.trim()));
        thread.addPost(p);
        return p;
    }
    List<DiscussionThread> all() { return threads.findAll(); }
    List<DiscussionThread> byCategory(String cat) {
        return threads.findAll().stream()
                .filter(t -> cat == null || cat.equals(t.getCategory()))
                .collect(Collectors.toList());
    }
}

class TeamService {
    private final Repository<Team> teams;
    private final Repository<User> users;
    TeamService(Repository<Team> teams, Repository<User> users) {
        this.teams = teams; this.users = users;
    }
    Team createTeam(String name, Long levelTwoActivityId, User leader) {
        Team t = new Team(name);
        t.setLevelTwoActivityId(levelTwoActivityId);
        t.setStatus("FORMING");
        teams.save(t);
        t.addMember(new TeamMember(t.getId(), leader.getId(), leader.getName(), "LEADER"));
        return t;
    }
    void addMember(Team team, User user) {
        if (team.getMembers().size() >= 5) throw new ValidationException("Team is full (max 5)");
        team.addMember(new TeamMember(team.getId(), user.getId(), user.getName(), "MEMBER"));
    }
    void assignMentor(Team team, Mentor mentor) {
        team.setMentorId(mentor.getId());
        team.setStatus("ACTIVE");
    }
    Optional<Team> findByMember(User user) {
        return teams.findAll().stream()
                .filter(t -> t.getMembers().stream()
                        .anyMatch(m -> m.getUserId().equals(user.getId())))
                .findFirst();
    }
}

class ProjectService {
    private final Repository<ImpactProject> projects;
    ProjectService(Repository<ImpactProject> p) { this.projects = p; }
    ImpactProject create(Team team, String title, ChallengeArea area,
                         String desc, LocalDate start, LocalDate end) {
        ImpactProject p = new ImpactProject(team.getId(), title, area, desc);
        p.start(start, end);
        return projects.save(p);
    }
    Milestone addMilestone(ImpactProject project, String title, LocalDate due) {
        Milestone m = new Milestone(project.getId(), title, due);
        project.addMilestone(m);
        return m;
    }
    Submission submit(ImpactProject project, String title, String fileUrl, String notes) {
        Submission s = new Submission(project.getId(), title, fileUrl, notes);
        project.setStatus(ProjectStatus.SUBMITTED);
        return s;
    }
}

class AssessmentService {
    private final Repository<Certificate> certificates;
    AssessmentService(Repository<Certificate> c) { this.certificates = c; }
    Certificate assessAndCertify(AssessmentStrategy strategy, Assessment assessment,
                                 User participant, double rawScore) {
        double finalScore = strategy.calculateScore(rawScore);
        boolean passed = strategy.isPassing(finalScore);
        System.out.printf("[assess] %s | raw=%.1f final=%.1f passed=%s%n",
                strategy.getName(), rawScore, finalScore, passed);
        if (!passed) return null;
        Certificate c = new Certificate(participant.getId(), participant.getName(),
                assessment.getTitle());
        c.setActivityId(assessment.getActivityId());
        return certificates.save(c);
    }
}

class NotificationService {
    void notify(String channel, User recipient, String subject, String msg) {
        NotificationFactory.create(channel, recipient.getId(), subject, msg).send();
    }
}