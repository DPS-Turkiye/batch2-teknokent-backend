<?php
// Local Moodle only: registers Menton office hours as a site-wide LTI 1.3 tool, and makes a test course with a
// teacher and a student who can open it. Safe to run again; it reuses what is already there.
// `docker compose --profile moodle up` runs it once Moodle is installed (the moodle-setup service). It seeds
// Moodle's own database through Moodle's API, so it cannot be a Flyway migration of this backend.
// It prints the LTI_* values for the backend's .env.

define('CLI_SCRIPT', true);
require('/bitnami/moodle/config.php');
require_once($CFG->dirroot . '/mod/lti/locallib.php');
require_once($CFG->dirroot . '/course/lib.php');
require_once($CFG->dirroot . '/course/modlib.php');
require_once($CFG->dirroot . '/user/lib.php');
require_once($CFG->dirroot . '/enrol/manual/lib.php');

\core\session\manager::set_user(get_admin());

$frontend = getenv('MENTON_FRONTEND_URL') ?: 'http://localhost:3000';
// Moodle's server fetches the tool's key set from the backend, which is the app service on the compose network.
$backendfromdocker = getenv('MENTON_BACKEND_URL_FROM_MOODLE') ?: 'http://app:8080';
$toolname = 'Menton ofis saatleri';

$type = $DB->get_record('lti_types', ['name' => $toolname]);
if (!$type) {
    $type = new stdClass();
    $type->state = LTI_TOOL_STATE_CONFIGURED;
    $config = new stdClass();
    $config->lti_typename = $toolname;
    $config->lti_toolurl = $frontend . '/lti/launch';
    $config->lti_description = 'Office hours on Menton (local development).';
    $config->lti_ltiversion = LTI_VERSION_1P3;
    $config->lti_keytype = LTI_JWK_KEYSET;
    $config->lti_publickeyset = $backendfromdocker . '/api/lti/jwks';
    $config->lti_initiatelogin = $frontend . '/lti/login';
    $config->lti_redirectionuris = $frontend . '/lti/launch';
    $config->lti_coursevisible = LTI_COURSEVISIBLE_ACTIVITYCHOOSER;
    // Menton refuses to be framed (X-Frame-Options: DENY), so the tool opens in a new window.
    $config->lti_launchcontainer = LTI_LAUNCH_CONTAINER_WINDOW;
    $config->lti_sendname = LTI_SETTING_ALWAYS;
    $config->lti_sendemailaddr = LTI_SETTING_ALWAYS;
    $config->lti_acceptgrades = LTI_SETTING_NEVER;
    $config->lti_contentitem = 0;
    $config->lti_forcessl = 0;
    $typeid = lti_add_type($type, $config);
    $type = $DB->get_record('lti_types', ['id' => $typeid], '*', MUST_EXIST);
}

$course = $DB->get_record('course', ['shortname' => 'CENG 101']);
if (!$course) {
    $course = create_course((object) [
        'fullname' => 'Introduction to Computing',
        'shortname' => 'CENG 101',
        'category' => $DB->get_field_select('course_categories', 'MIN(id)', '1=1'),
        'format' => 'topics',
    ]);
}

$people = [
    ['username' => 'teacher1', 'password' => 'Teacher-local-1', 'firstname' => 'Ali', 'lastname' => 'Kaya',
        'email' => 'teacher1@example.com', 'role' => 'editingteacher'],
    ['username' => 'student1', 'password' => 'Student-local-1', 'firstname' => 'Ayşe', 'lastname' => 'Yılmaz',
        'email' => 'student1@example.com', 'role' => 'student'],
];
$manual = enrol_get_plugin('manual');
$instance = $DB->get_record('enrol', ['courseid' => $course->id, 'enrol' => 'manual'], '*', MUST_EXIST);
foreach ($people as $person) {
    $user = $DB->get_record('user', ['username' => $person['username']]);
    if (!$user) {
        $id = user_create_user((object) [
            'username' => $person['username'], 'password' => $person['password'], 'auth' => 'manual',
            'firstname' => $person['firstname'], 'lastname' => $person['lastname'], 'email' => $person['email'],
            'confirmed' => 1, 'mnethostid' => $CFG->mnet_localhost_id,
        ], true, false);
        $user = $DB->get_record('user', ['id' => $id], '*', MUST_EXIST);
    }
    $roleid = $DB->get_field('role', 'id', ['shortname' => $person['role']], MUST_EXIST);
    $manual->enrol_user($instance, $user->id, $roleid);
}

$activity = $DB->get_record('lti', ['course' => $course->id, 'typeid' => $type->id]);
if (!$activity) {
    create_module((object) [
        'modulename' => 'lti', 'course' => $course->id, 'section' => 0, 'visible' => 1,
        'name' => 'Ofis saatleri', 'introeditor' => ['text' => '', 'format' => FORMAT_HTML, 'itemid' => 0],
        'typeid' => $type->id, 'toolurl' => '', 'securetoolurl' => '',
        'launchcontainer' => LTI_LAUNCH_CONTAINER_DEFAULT,
        'instructorchoicesendname' => 1, 'instructorchoicesendemailaddr' => 1, 'instructorchoiceacceptgrades' => 0,
        'grade' => 0, 'showtitlelaunch' => 1, 'showdescriptionlaunch' => 0,
    ]);
}

echo "LTI_PLATFORM_ISSUER={$CFG->wwwroot}\n";
echo "LTI_CLIENT_ID={$type->clientid}\n";
echo "LTI_DEPLOYMENT_IDS={$type->id}\n";
echo "LTI_PLATFORM_AUTH_URL={$CFG->wwwroot}/mod/lti/auth.php\n";
echo "LTI_PLATFORM_JWKS_URL=http://moodle:8080/mod/lti/certs.php\n";
echo "LTI_TOOL_LAUNCH_URL={$frontend}/lti/launch\n";
echo "Course: {$CFG->wwwroot}/course/view.php?id={$course->id}\n";
