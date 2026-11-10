/**
 * ============================================================================
 * YEP - Youth Empowerment Project
 * Single-File OOP Implementation
 * ============================================================================
 *
 * COMPILE:  javac YEP.java
 * RUN:      java YEP
 *
 * WHAT THIS FILE DEMONSTRATES (assignment requirement mapping):
 *
 *   Abstraction     -> BaseEntity, User, LearningActivity, Notification
 *   Inheritance     -> 5 user types, 2 activity types, 3 notification types
 *   Polymorphism    -> getDashboardSummary(), getLevelFeatures(), getType()
 *   Encapsulation   -> private fields + validation in setters
 *   Interfaces      -> Repository<T>, AssessmentStrategy
 *   Design Patterns -> Factory, Strategy, Repository, Singleton
 *
 *   Level 1 Virtual Exchange -> LevelOneActivity (large groups, discussions)
 *   Level 2 Virtual Exchange -> LevelTwoActivity (teams, mentors, projects)
 *   Discussion Board         -> DiscussionThread + DiscussionPost
 *   Team Formation           -> Team + TeamMember
 *   Impact Project           -> ImpactProject + Milestone + Submission
 *   Assessment               -> Quiz / Project / Peer strategies
 *   Certificates             -> Verifiable digital certificates
 *   Notifications            -> Email / SMS / In-App (polymorphic dispatch)
 *
 * NOTE: Only ONE class in the file can be `public` — the entry-point `YEP`.
 *       All other classes are package-private (legal in Java).
 * ============================================================================
 */

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

// ============================================================================
// 1. ENUMS
// ============================================================================

enum UserRole {
    YOUTH("Youth Participant"),
    FACILITATOR("Facilitator"),
    MENTOR("Mentor"),
    ADMIN("Administrator"),
    PARTNER("Partner Organisation");
    private final String label;
    UserRole(String l) { this.label = l; }
    public String getLabel() { return label; }
}

enum AccountStatus { PENDING, ACTIVE, SUSPENDED }

enum ActivityType { LIVE_SESSION, SELF_PACED, WORKSHOP, VIRTUAL_EXCHANGE }

enum ActivityLevel {
    LEVEL_ONE(1, "Level One - Large-group learning & discussion"),
    LEVEL_TWO(2, "Level Two - Selected teams on impact projects");
    private final int value;
    private final String desc;
    ActivityLevel(int v, String d) { this.value = v; this.desc = d; }
    public int getValue()          { return value; }
    public String getDescription() { return desc; }
    public static ActivityLevel fromInt(int v) { return v == 1 ? LEVEL_ONE : LEVEL_TWO; }
}

enum ChallengeArea {
    EMPLOYMENT("Employment"), EDUCATION("Education"),
    AGRICULTURE("Agriculture"), HEALTH("Health"),
    CLIMATE_CHANGE("Climate Change"), DIGITAL_INCLUSION("Digital Inclusion");
    private final String label;
    ChallengeArea(String l) { this.label = l; }
    public String getLabel() { return label; }
}

enum ProjectStatus { DRAFT, IN_PROGRESS, SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED }

enum EnrollmentStatus { IN_PROGRESS, COMPLETED, DROPPED }

enum NotificationType { EMAIL, SMS, IN_APP }

// ============================================================================
// 2. EXCEPTIONS
// ============================================================================

class YepException extends RuntimeException {
    public YepException(String m)             { super(m); }
    public YepException(String m, Throwable c){ super(m, c); }
}

class ValidationException extends YepException {
    public ValidationException(String m) { super(m); }
}

class AuthenticationException extends YepException {
    public AuthenticationException(String m) { super(m); }
}

// ============================================================================
// 3. UTILITIES
// ============================================================================

final class ValidationUtil {
    private ValidationUtil() {}
    static boolean isBlank(String s)    { return s == null || s.trim().isEmpty(); }
    static boolean isEmail(String s)    { return s != null && s.matches("^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$"); }
    static boolean isPhone(String s)    { return s != null && s.matches("^\\+?[0-9]{7,15}$"); }
    static boolean minLength(String s, int n) { return s != null && s.length() >= n; }
    static boolean isAge18to35(Integer age)    { return age != null && age >= 18 && age <= 35; }
}

final class PasswordUtil {
    private PasswordUtil() {}
    static String generateSalt() {
        byte[] s = new byte[16];
        new SecureRandom().nextBytes(s);
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

final class DateUtil {
    private DateUtil() {}
    static String format(LocalDate d) { return d == null ? "-" : d.toString(); }
}

// ============================================================================
// 4. BASE ENTITY - root of every model
// ============================================================================

abstract class BaseEntity {
    private static long SEQ = 1;
    private final Long id;
    private final LocalDateTime createdAt;

    protected BaseEntity() {
        this.id = SEQ++;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId()                    { return id; }
    public LocalDateTime getCreatedAt()    { return createdAt; }

    /** Polymorphic display label used in dashboards and logs. */
    public abstract String displayLabel();

    @Override public String toString() { return displayLabel(); }
}

// ============================================================================
// 5. USER HIERARCHY - abstraction + inheritance + polymorphism
// ============================================================================

class Profile {
    private String bio;
    private String photoUrl;
    private String languagePreference = "en";
    public String getBio()                          { return bio; }
    public void setBio(String v)                    { this.bio = v; }
    public String getPhotoUrl()                     { return photoUrl; }
    public void setPhotoUrl(String v)               { this.photoUrl = v; }
    public String getLanguagePreference()           { return languagePreference; }
    public void setLanguagePreference(String v)     { this.languagePreference = v; }
}

abstract class User extends BaseEntity {
    private String name;
    private String email;
    private String phone;
    private String passwordHash;
    private String passwordSalt;
    private AccountStatus status = AccountStatus.PENDING;
    private final Profile profile = new Profile();

    protected User(String name, String email, String phone) {
        setName(name); setEmail(email); this.phone = phone;
    }

    // --- Abstract behaviour each subclass must define ---
    public abstract UserRole getRole();
    public abstract boolean isEligibleForLevelTwo();
    public abstract String getDashboardSummary();

    // --- Shared behaviour ---
    public void activate()    { this.status = AccountStatus.ACTIVE; }
    public void suspend()     { this.status = AccountStatus.SUSPENDED; }
    public boolean isActive() { return status == AccountStatus.ACTIVE; }

    @Override public String displayLabel() { return getRole().getLabel() + " · " + name; }

    public String getName()               { return name; }
    public void setName(String v)         {
        if (ValidationUtil.isBlank(v)) throw new ValidationException("Name required");
        this.name = v.trim();
    }
    public String getEmail()              { return email; }
    public void setEmail(String v)        {
        if (!ValidationUtil.isEmail(v)) throw new ValidationException("Invalid email");
        this.email = v.trim().toLowerCase();
    }
    public String getPhone()              { return phone; }
    public void setPhone(String v)        { this.phone = v; }
    public String getPasswordHash()       { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public String getPasswordSalt()       { return passwordSalt; }
    public void setPasswordSalt(String v) { this.passwordSalt = v; }
    public AccountStatus getStatus()      { return status; }
    public Profile getProfile()           { return profile; }
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
                country == null ? "-" : country, levelTwoEligible ? "YES" : "no");
    }
    public Integer getAge()                  { return age; }
    public void setAge(Integer v)            {
        if (v != null && (v < 18 || v > 35)) throw new ValidationException("Age must be 18-35");
        this.age = v;
    }
    public String getGender()                { return gender; }
    public void setGender(String v)          { this.gender = v; }
    public String getCountry()               { return country; }
    public void setCountry(String v)         { this.country = v; }
    public String getDistrict()              { return district; }
    public void setDistrict(String v)        { this.district = v; }
    public String getEducationLevel()        { return educationLevel; }
    public void setEducationLevel(String v)  { this.educationLevel = v; }
    public boolean isLevelTwoEligible()      { return levelTwoEligible; }
    public void setLevelTwoEligible(boolean v){ this.levelTwoEligible = v; }
}

class Facilitator extends User {
    private String expertise;
    public Facilitator(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole()              { return UserRole.FACILITATOR; }
    @Override public boolean isEligibleForLevelTwo() { return false; }
    @Override public String getDashboardSummary()    {
        return "Facilitator · " + getName() + (expertise != null ? " · " + expertise : "");
    }
    public String getExpertise()       { return expertise; }
    public void setExpertise(String v) { this.expertise = v; }
}

class Mentor extends User {
    private String expertise, organisation;
    public Mentor(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole()              { return UserRole.MENTOR; }
    @Override public boolean isEligibleForLevelTwo() { return false; }
    @Override public String getDashboardSummary()    {
        return "Mentor · " + getName() + (organisation != null ? " @ " + organisation : "");
    }
    public String getExpertise()       { return expertise; }
    public void setExpertise(String v) { this.expertise = v; }
    public String getOrganisation()    { return organisation; }
    public void setOrganisation(String v) { this.organisation = v; }
}

class Administrator extends User {
    public Administrator(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole()              { return UserRole.ADMIN; }
    @Override public boolean isEligibleForLevelTwo() { return false; }
    @Override public String getDashboardSummary()    { return "Administrator · " + getName(); }
}

class PartnerOrganisation extends User {
    private String orgType;
    public PartnerOrganisation(String n, String e, String p) { super(n, e, p); }
    @Override public UserRole getRole()              { return UserRole.PARTNER; }
    @Override public boolean isEligibleForLevelTwo() { return false; }
    @Override public String getDashboardSummary()    {
        return "Partner · " + getName() + (orgType != null ? " (" + orgType + ")" : "");
    }
    public String getOrgType()       { return orgType; }
    public void setOrgType(String v) { this.orgType = v; }
}

// ============================================================================
// 6. ACTIVITY HIERARCHY - abstraction for Level 1 vs Level 2
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

    // --- Abstract: each level defines its own identity ---
    public abstract ActivityLevel getLevel();
    public abstract List<String> getLevelFeatures();
    public abstract boolean supportsTeamFormation();

    public void publish()   { this.published = true; }
    public void unpublish() { this.published = false; }

    @Override public String displayLabel() { return "[L" + getLevel().getValue() + "] " + title; }

    public String getTitle()              { return title; }
    public String getDescription()        { return description; }
    public ActivityType getType()         { return type; }
    public String getCategory()           { return category; }
    public LocalDate getStartDate()       { return startDate; }
    public LocalDate getEndDate()         { return endDate; }
    public String getContentUrl()         { return contentUrl; }
    public boolean isPublished()          { return published; }
    public Long getFacilitatorId()        { return facilitatorId; }
    public void setFacilitatorId(Long v)  { this.facilitatorId = v; }
    public int getMaxParticipants()       { return maxParticipants; }
    public void setMaxParticipants(int v) { this.maxParticipants = v; }
}

class LevelOneActivity extends LearningActivity {
    public LevelOneActivity(String t, String d, ActivityType ty, String c,
                            LocalDate s, LocalDate e, String u) {
        super(t, d, ty, c, s, e, u);
    }
    @Override public ActivityLevel getLevel()          { return ActivityLevel.LEVEL_ONE; }
    @Override public boolean supportsTeamFormation()   { return false; }
    @Override public List<String> getLevelFeatures()   {
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
    @Override public ActivityLevel getLevel()          { return ActivityLevel.LEVEL_TWO; }
    @Override public boolean supportsTeamFormation()   { return true; }
    @Override public List<String> getLevelFeatures()   {
        return List.of(
            "Selected participants only",
            "Team formation (3-5 members)",
            "Mentor assigned per team",
            "Impact-project development",
            "Milestones, submissions & assessment",
            "Cross-border virtual exchange"
        );
    }
    public int getMaxTeams()    { return maxTeams; }
    public int getMinTeamSize() { return minTeamSize; }
    public int getMaxTeamSize() { return maxTeamSize; }
}

// ============================================================================
// 7. SUPPORTING MODELS
// ============================================================================

class Enrollment extends BaseEntity {
    private Long userId, activityId;
    private double progress;
    private EnrollmentStatus status = EnrollmentStatus.IN_PROGRESS;

    public Enrollment(Long u, Long a) { this.userId = u; this.activityId = a; }
    public void updateProgress(double pct) {
        if (pct < 0 || pct > 100) throw new ValidationException("Progress must be 0-100");
        this.progress = pct;
        if (pct >= 100) this.status = EnrollmentStatus.COMPLETED;
    }
    @Override public String displayLabel() { return "Enrollment #" + getId(); }
    public Long getUserId()             { return userId; }
    public Long getActivityId()         { return activityId; }
    public double getProgress()         { return progress; }
    public EnrollmentStatus getStatus() { return status; }
}

class Assessment extends BaseEntity {
    private Long activityId;
    private String title;
    private int maxScore = 100, passScore = 50;

    public Assessment(Long a, String t, int max) {
        this.activityId = a; this.title = t;
        this.maxScore = max;
        this.passScore = (int) Math.ceil(max * 0.5);
    }
    public boolean isPassed(double score) { return score >= passScore; }
    @Override public String displayLabel() { return "Assessment: " + title; }
    public Long getActivityId() { return activityId; }
    public String getTitle()    { return title; }
    public int getMaxScore()    { return maxScore; }
    public int getPassScore()   { return passScore; }
}

class Certificate extends BaseEntity {
    private String certificateNumber;
    private Long userId, activityId, projectId;
    private String participantName, title, qrCodeUrl;
    private LocalDateTime issueDate = LocalDateTime.now();

    public Certificate(Long uid, String name, String title) {
        this.userId = uid;
        this.participantName = name;
        this.title = title;
        this.certificateNumber = "YEP-" + System.currentTimeMillis() + "-" +
                                 (1000 + new Random().nextInt(9000));
        this.qrCodeUrl = "https://verify.yep.ug/" + certificateNumber;
    }
    @Override public String displayLabel() { return "Certificate " + certificateNumber; }
    public String getCertificateNumber() { return certificateNumber; }
    public Long getUserId()              { return userId; }
    public Long getActivityId()          { return activityId; }
    public void setActivityId(Long v)    { this.activityId = v; }
    public Long getProjectId()           { return projectId; }
    public void setProjectId(Long v)     { this.projectId = v; }
    public String getParticipantName()   { return participantName; }
    public String getTitle()             { return title; }
    public String getQrCodeUrl()         { return qrCodeUrl; }
    public LocalDateTime getIssueDate()  { return issueDate; }
}

class TeamMember extends BaseEntity {
    private Long teamId, userId;
    private String roleInTeam = "MEMBER", userName;

    public TeamMember(Long team, Long user, String name, String role) {
        this.teamId = team; this.userId = user;
        this.userName = name; this.roleInTeam = role;
    }
    @Override public String displayLabel() { return userName + " (" + roleInTeam + ")"; }
    public Long getTeamId()          { return teamId; }
    public Long getUserId()          { return userId; }
    public String getRoleInTeam()    { return roleInTeam; }
    public String getUserName()      { return userName; }
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

    public String getTeamName()                { return teamName; }
    public String getStatus()                  { return status; }
    public void setStatus(String v)            { this.status = v; }
    public Long getMentorId()                  { return mentorId; }
    public void setMentorId(Long v)            { this.mentorId = v; }
    public Long getLevelTwoActivityId()        { return levelTwoActivityId; }
    public void setLevelTwoActivityId(Long v)  { this.levelTwoActivityId = v; }
    public List<TeamMember> getMembers()       { return Collections.unmodifiableList(members); }
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
    public Long getTeamId()                    { return teamId; }
    public String getTitle()                   { return title; }
    public ChallengeArea getChallengeArea()    { return challengeArea; }
    public String getDescription()             { return description; }
    public ProjectStatus getStatus()           { return status; }
    public void setStatus(ProjectStatus v)     { this.status = v; }
    public LocalDate getStartDate()            { return startDate; }
    public LocalDate getEndDate()              { return endDate; }
    public List<Milestone> getMilestones()     { return Collections.unmodifiableList(milestones); }
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
    public Long getProjectId()      { return projectId; }
    public String getTitle()        { return title; }
    public LocalDate getDueDate()   { return dueDate; }
    public boolean isCompleted()    { return completed; }
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
    public Long getProjectId()  { return projectId; }
    public String getTitle()    { return title; }
    public String getFileUrl()  { return fileUrl; }
    public Double getScore()    { return score; }
    public String getFeedback() { return feedback; }
}

// ============================================================================
// 8. DISCUSSION (the assignment insists on participant communication)
// ============================================================================

class DiscussionPost extends BaseEntity {
    private Long threadId, authorId;
    private String authorName, body;

    public DiscussionPost(Long t, Long a, String name, String body) {
        this.threadId = t; this.authorId = a; this.authorName = name; this.body = body;
    }
    @Override public String displayLabel() {
        return authorName + ": " + body.substring(0, Math.min(40, body.length()));
    }
    public Long getThreadId()     { return threadId; }
    public String getAuthorName() { return authorName; }
    public String getBody()       { return body; }
}

class DiscussionThread extends BaseEntity {
    private Long activityId, authorId;
    private String authorName, category, title, body;
    private final List<DiscussionPost> posts = new ArrayList<>();

    public DiscussionThread(Long activity, Long author, String name,
                            String cat, String title, String body) {
        if (ValidationUtil.isBlank(title)) throw new ValidationException("Thread title required");
        if (ValidationUtil.isBlank(body))  throw new ValidationException("Thread body required");
        this.activityId = activity;
        this.authorId = author;
        this.authorName = name;
        this.category = cat;
        this.title = title.trim();
        this.body = body.trim();
    }
    public void addPost(DiscussionPost p) { posts.add(p); }
    @Override public String displayLabel() { return "Thread: " + title; }
    public Long getActivityId()            { return activityId; }
    public String getAuthorName()          { return authorName; }
    public String getCategory()            { return category; }
    public String getTitle()               { return title; }
    public String getBody()                { return body; }
    public List<DiscussionPost> getPosts() { return Collections.unmodifiableList(posts); }
    public int getReplyCount()             { return posts.size(); }
}

// ============================================================================
// 9. NOTIFICATION HIERARCHY - polymorphic dispatch
// ============================================================================

abstract class Notification extends BaseEntity {
    private Long recipientId;
    private String subject, message;

    protected Notification(Long r, String s, String m) {
        this.recipientId = r; this.subject = s; this.message = m;
    }
    public abstract NotificationType getType();
    public abstract void send();
    @Override public String displayLabel() { return "[" + getType() + "] " + subject; }
    public Long getRecipientId() { return recipientId; }
    public String getSubject()   { return subject; }
    public String getMessage()   { return message; }
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
// 10. FACTORIES - centralize object creation
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
// 11. STRATEGY PATTERN - different assessment rules
// ============================================================================

interface AssessmentStrategy {
    double calculateScore(double rawScore);
    boolean isPassing(double finalScore);
    String getName();
}

class QuizAssessmentStrategy implements AssessmentStrategy {
    @Override public double calculateScore(double r) { return r; }
    @Override public boolean isPassing(double s)     { return s >= 50; }
    @Override public String getName()                { return "Quiz (50% to pass)"; }
}
class ProjectAssessmentStrategy implements AssessmentStrategy {
    @Override public double calculateScore(double r) { return r * 1.1; }
    @Override public boolean isPassing(double s)     { return s >= 60; }
    @Override public String getName()                { return "Project (60% to pass, +10% bonus)"; }
}
class PeerAssessmentStrategy implements AssessmentStrategy {
    @Override public double calculateScore(double r) { return r * 0.9; }
    @Override public boolean isPassing(double s)     { return s >= 40; }
    @Override public String getName()                { return "Peer (40% to pass, -10% penalty)"; }
}

// ============================================================================
// 12. REPOSITORY PATTERN - generic in-memory store
// ============================================================================

interface Repository<T extends BaseEntity> {
    T save(T entity);
    Optional<T> findById(Long id);
    List<T> findAll();
    void delete(Long id);
}

class InMemoryRepository<T extends BaseEntity> implements Repository<T> {
    private final Map<Long, T> store = new LinkedHashMap<>();
    @Override public T save(T entity) {
        store.put(entity.getId(), entity);
        return entity;
    }
    @Override public Optional<T> findById(Long id) { return Optional.ofNullable(store.get(id)); }
    @Override public List<T> findAll()             { return new ArrayList<>(store.values()); }
    @Override public void delete(Long id)          { store.remove(id); }
}

// ============================================================================
// 13. SERVICES - business logic
// ============================================================================

class AuthService {
    private final Repository<User> users;
    AuthService(Repository<User> users) { this.users = users; }

    User register(String role, String name, String email, String phone, String password,
                  Integer age, String gender, String country, String district,
                  String education, String expertise, String organisation) {
        if (ValidationUtil.isBlank(name))           throw new ValidationException("Name required");
        if (!ValidationUtil.isEmail(email))         throw new ValidationException("Invalid email");
        if (!ValidationUtil.minLength(password, 6)) throw new ValidationException("Password must be >= 6 chars");
        if (users.findAll().stream().anyMatch(u -> u.getEmail().equalsIgnoreCase(email)))
            throw new ValidationException("Email already registered");

        User u = UserFactory.create(role, name, email, phone);
        if (u instanceof YouthParticipant y) {
            if (!ValidationUtil.isAge18to35(age)) throw new ValidationException("Youth must be aged 18-35");
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
                .filter(a -> enrolled.contains(a.getId()))
                .collect(Collectors.toList());
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

    List<DiscussionThread> all()                              { return threads.findAll(); }
    List<DiscussionThread> byCategory(String cat)             {
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
        if (team.getMembers().size() >= 5)
            throw new ValidationException("Team is full (max 5)");
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
        System.out.printf("    Assessment: %s | Raw: %.1f | Final: %.1f | Passed: %s%n",
                strategy.getName(), rawScore, finalScore, passed);
        if (!passed) return null;
        Certificate c = new Certificate(participant.getId(),
                participant.getName(), assessment.getTitle());
        c.setActivityId(assessment.getActivityId());
        return certificates.save(c);
    }

    boolean verify(Repository<Certificate> certs, String number) {
        return certs.findAll().stream().anyMatch(c -> c.getCertificateNumber().equals(number));
    }
}

class NotificationService {
    void notify(String channel, User recipient, String subject, String msg) {
        NotificationFactory.create(channel, recipient.getId(), subject, msg).send();
    }
}

// ============================================================================
// 14. PUBLIC ENTRY POINT - live demo of the full YEP flow
// ============================================================================

public class YEP {

    public static void main(String[] args) {

        banner("YOUTH EMPOWERMENT PROJECT (YEP) - SINGLE FILE DEMO");

        // -------- wire up repositories --------
        Repository<User>               users       = new InMemoryRepository<>();
        Repository<LearningActivity>   activities  = new InMemoryRepository<>();
        Repository<Enrollment>         enrolls     = new InMemoryRepository<>();
        Repository<DiscussionThread>   threads     = new InMemoryRepository<>();
        Repository<DiscussionPost>     posts       = new InMemoryRepository<>();
        Repository<Team>               teams       = new InMemoryRepository<>();
        Repository<ImpactProject>      projects    = new InMemoryRepository<>();
        Repository<Certificate>        certs       = new InMemoryRepository<>();

        // -------- wire up services --------
        AuthService        auth        = new AuthService(users);
        LearningService    learning    = new LearningService(activities, enrolls);
        DiscussionService  discussions = new DiscussionService(threads, posts);
        TeamService        teamSvc     = new TeamService(teams, users);
        ProjectService     projectSvc  = new ProjectService(projects);
        AssessmentService  assessSvc   = new AssessmentService(certs);
        NotificationService notify     = new NotificationService();

        // -------- 1. Register users of every type --------
        section("1. Register users (OOP inheritance via UserFactory)");
        YouthParticipant alice = (YouthParticipant) auth.register(
                "YOUTH", "Alice Nakato", "alice@yep.ug", "+256770000001",
                "password", 22, "Female", "Uganda", "Kampala", "BSc Computer Science", null, null);
        YouthParticipant bob   = (YouthParticipant) auth.register(
                "YOUTH", "Bob Okello", "bob@yep.ug", "+256770000002",
                "password", 26, "Male", "Uganda", "Gulu", "Diploma in Agriculture", null, null);
        YouthParticipant charlie = (YouthParticipant) auth.register(
                "YOUTH", "Charlie Mwangi", "charlie@yep.ke", "+254780000003",
                "password", 24, "Male", "Kenya", "Nairobi", "BA Communications", null, null);
        Facilitator david = (Facilitator) auth.register(
                "FACILITATOR", "David Ssem", "david@yep.ug", "+256770000005",
                "password", null, null, null, null, null, "Digital Literacy", null);
        Mentor grace = (Mentor) auth.register(
                "MENTOR", "Grace Achieng", "grace@yep.ug", "+256770000004",
                "password", null, null, null, null, null, "AgriTech", "Makerere University");
        auth.register("ADMIN", "YEP Admin", "admin@yep.ug", "+256770000006",
                "password", null, null, null, null, null, null, null);

        System.out.println("    Registered " + users.findAll().size() + " users of " +
                users.findAll().stream().map(User::getRole).distinct().count() + " role types");

        // -------- 2. Create Level 1 & Level 2 activities --------
        section("2. Create Level 1 and Level 2 virtual exchange activities");
        LearningActivity aiCourse = learning.create(1, "AI Literacy Fundamentals",
                "Responsible AI, misinformation and critical thinking",
                ActivityType.SELF_PACED, "AI Literacy",
                LocalDate.now(), LocalDate.now().plusDays(14),
                "https://content.yep.ug/ai101", david.getId());
        LearningActivity storytelling = learning.create(1, "Digital Storytelling Workshop",
                "Video, audio and written storytelling for impact",
                ActivityType.WORKSHOP, "Media Literacy",
                LocalDate.now(), LocalDate.now().plusDays(21),
                "https://content.yep.ug/story", david.getId());
        LearningActivity l2Agri = learning.create(2, "Level Two: AgriTech Impact Teams",
                "Team-based impact projects in agriculture",
                ActivityType.VIRTUAL_EXCHANGE, "Agriculture",
                LocalDate.now(), LocalDate.now().plusDays(60),
                "https://content.yep.ug/l2-agri", david.getId());

        activities.findAll().forEach(a -> {
            System.out.println("    " + a.displayLabel() + "  by " + a.getLevel().getDescription());
            a.getLevelFeatures().forEach(f -> System.out.println("        · " + f));
        });

        // -------- 3. Enroll Alice and Bob in Level 1 --------
        section("3. Level One - enroll participants");
        learning.enroll(alice, aiCourse);
        learning.enroll(bob, aiCourse);
        learning.enroll(charlie, storytelling);
        System.out.println("    Alice, Bob enrolled in AI Literacy");
        System.out.println("    Charlie enrolled in Digital Storytelling");
        System.out.println("    Alice's activities: " +
                learning.forUser(alice.getId()).stream().map(LearningActivity::getTitle).toList());

        // -------- 4. Discussion board (per assignment) --------
        section("4. Discussion board - participants exchange ideas");
        DiscussionThread thread = discussions.createThread(
                alice, aiCourse.getId(), "AI Ethics",
                "How do we spot deepfakes?",
                "I saw a video claiming to be a minister - the lips don't match. How do I verify?");
        discussions.reply(thread, bob,
                "Cross-check the source on the ministry website and look for the clip on 2+ reputable outlets.");
        discussions.reply(thread, charlie,
                "Also check metadata via a tool like InVID. Reverse image search works too.");
        System.out.println("    Thread: " + thread.getTitle() + "  (" + thread.getReplyCount() + " replies)");
        thread.getPosts().forEach(p -> System.out.println("        · " + p.getAuthorName() + ": " + p.getBody()));

        // -------- 5. Assessment with Strategy pattern --------
        section("5. Assessment & certification (Strategy pattern)");
        Assessment quiz = new Assessment(aiCourse.getId(), "AI Fundamentals Quiz", 100);
        Certificate aliceCert = assessSvc.assessAndCertify(
                new QuizAssessmentStrategy(), quiz, alice, 85);
        assessSvc.assessAndCertify(new QuizAssessmentStrategy(), quiz, bob, 42);
        System.out.println("    Alice cert: " + (aliceCert != null ? aliceCert.getCertificateNumber() : "none"));
        System.out.println("    Verify Alice's cert: " +
                assessSvc.verify(certs, aliceCert.getCertificateNumber()));

        // -------- 6. Promote to Level 2 - team formation --------
        section("6. Level Two - team formation with mentor");
        alice.setLevelTwoEligible(true);
        bob.setLevelTwoEligible(true);
        charlie.setLevelTwoEligible(true);

        Team agriTeam = teamSvc.createTeam("Agri Innovators", l2Agri.getId(), alice);
        teamSvc.addMember(agriTeam, bob);
        teamSvc.addMember(agriTeam, charlie);
        teamSvc.assignMentor(agriTeam, grace);
        System.out.println("    Team: " + agriTeam.getTeamName() + "  (status: " + agriTeam.getStatus() + ")");
        agriTeam.getMembers().forEach(m -> System.out.println("        · " + m));

        // -------- 7. Impact project development --------
        section("7. Impact project development");
        ImpactProject project = projectSvc.create(
                agriTeam, "Smart Agri Advisor", ChallengeArea.AGRICULTURE,
                "Mobile-first crop advisory service for smallholder farmers",
                LocalDate.now(), LocalDate.now().plusMonths(3));
        Milestone m1 = projectSvc.addMilestone(project, "Problem statement", LocalDate.now().plusDays(7));
        Milestone m2 = projectSvc.addMilestone(project, "MVP mockup",       LocalDate.now().plusDays(30));
        Milestone m3 = projectSvc.addMilestone(project, "Pilot deployment", LocalDate.now().plusDays(60));
        m1.complete();
        System.out.println("    Project: " + project.getTitle() + "  (" + project.getStatus() + ")");
        project.getMilestones().forEach(m ->
                System.out.println("        · " + m.getTitle() + " due " + m.getDueDate() +
                        (m.isCompleted() ? "  [DONE]" : "")));

        // -------- 8. Submission + project assessment --------
        section("8. Submit deliverable & grade (Project assessment strategy)");
        Submission sub = projectSvc.submit(project, "Project Report v1",
                "https://files.yep.ug/agri-report.pdf",
                "Includes farmer interviews and initial wireframes");
        sub.grade(78, "Strong problem framing; add more market analysis");
        System.out.println("    Submission: " + sub.getTitle() + "  score=" + sub.getScore());

        Assessment projectAssess = new Assessment(project.getId(), "Level Two Project", 100);
        Certificate teamCert = assessSvc.assessAndCertify(
                new ProjectAssessmentStrategy(), projectAssess, alice, 78);

        // -------- 9. Notifications (polymorphic dispatch) --------
        section("9. Notifications - polymorphic dispatch");
        notify.notify("EMAIL", alice,   "Welcome to YEP",       "Your account is active");
        notify.notify("SMS",   bob,     "Team Assigned",        "You joined Agri Innovators");
        notify.notify("INAPP", charlie, "New Reply",            "Someone replied to your thread");

        // -------- 10. Polymorphism showcase --------
        section("10. Polymorphism - each User subtype renders its own summary");
        users.findAll().forEach(u -> System.out.println("    " + u.getDashboardSummary()));

        section("10b. Polymorphism - each Activity subtype lists its features");
        activities.findAll().forEach(a -> {
            System.out.println("    " + a.displayLabel());
            a.getLevelFeatures().forEach(f -> System.out.println("        · " + f));
        });

        // -------- 11. Final state summary --------
        section("11. Final state");
        System.out.println("    Users:        " + users.findAll().size());
        System.out.println("    Activities:   " + activities.findAll().size() +
                          "  (L1=" + learning.byLevel(1).size() + ", L2=" + learning.byLevel(2).size() + ")");
        System.out.println("    Enrollments:  " + enrolls.findAll().size());
        System.out.println("    Discussions:  " + threads.findAll().size() +
                          " thread(s), " + posts.findAll().size() + " post(s)");
        System.out.println("    Teams:        " + teams.findAll().size());
        System.out.println("    Projects:     " + projects.findAll().size());
        System.out.println("    Certificates: " + certs.findAll().size());

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  DEMO COMPLETE");
        System.out.println("=".repeat(60));
    }

    private static void section(String title) {
        System.out.println("\n--- " + title + " ---");
    }

    private static void banner(String title) {
        System.out.println("=".repeat(60));
        System.out.println("  " + title);
        System.out.println("=".repeat(60));
    }
}