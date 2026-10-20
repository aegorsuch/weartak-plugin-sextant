const { readFileSync } = require('node:fs');
const { join } = require('node:path');

// Must match TAG, MARKER and the TAK.gov project in ci/publish_github_release.py.
const TAG = 'TAK.gov-Forge-CI-CD-Pipeline-Release';
const RELEASE_MARKER = /<!-- sextant-rolling-v1 (\{.*?\}) -->/;
const PROJECT_ID = '6910';
const APK = /^Sextant-[0-9A-Za-z._-]+\.apk$/;

function releaseState(release) {
  const match = RELEASE_MARKER.exec(release.body || '');
  let state;
  try {
    state = match && JSON.parse(match[1]);
  } catch {
    state = null;
  }
  if (!state || String(state.project_id) !== PROJECT_ID ||
      !/^[1-9]\d*$/.test(String(state.pipeline_id))) {
    throw new Error(`Release ${TAG} has no valid publisher metadata; refusing to notify testers.`);
  }
  return state;
}

async function canAssign(github, repo, tester) {
  try {
    await github.rest.issues.checkUserCanBeAssigned({ ...repo, assignee: tester });
    return true;
  } catch (error) {
    if (error.status === 404) return false;
    throw error;
  }
}

function isAssigned(issue, tester) {
  return (issue.assignees || []).some((a) => a.login.toLowerCase() === tester.toLowerCase());
}

async function createReleaseTestIssue({ github, context, core, tester }) {
  if (typeof tester !== 'string' || !/^[a-z\d](?:[a-z\d-]{0,37}[a-z\d])?$/i.test(tester)) {
    throw new Error('Set the RELEASE_TESTER repository variable to the tester GitHub username.');
  }
  const repo = context.repo;
  // Never trust the notification payload for content: re-read the published release.
  const { data: release } = await github.rest.repos.getReleaseByTag({ ...repo, tag: TAG });
  if (release.draft) {
    core.info('Release is still a draft; nothing to test yet.');
    return null;
  }
  const state = releaseState(release);
  const pipelineId = String(state.pipeline_id);
  if (context.eventName === 'repository_dispatch') {
    const requested = String((context.payload.client_payload || {}).pipeline_id || '');
    if (requested !== pipelineId) {
      core.info(`Pipeline ${requested} was superseded by ${pipelineId}; skipping.`);
      return null;
    }
  }
  const apk = (release.assets || []).find((a) => APK.test(a.name) && a.state === 'uploaded');
  if (!apk) throw new Error(`Release ${TAG} has no uploaded Sextant APK.`);

  const marker = `<!-- sextant-release-testing:pipeline-${pipelineId} -->`;
  const assignable = await canAssign(github, repo, tester);
  // Include closed issues so reruns cannot recreate a completed checklist.
  for await (const page of github.paginate.iterator(
    github.rest.issues.listForRepo,
    { ...repo, state: 'all', per_page: 100 },
  )) {
    const existing = page.data.find(
      (issue) => !issue.pull_request && (issue.body || '').includes(marker),
    );
    if (!existing) continue;
    if (existing.state === 'open' && assignable && !isAssigned(existing, tester)) {
      const { data } = await github.rest.issues.addAssignees({
        ...repo, issue_number: existing.number, assignees: [tester],
      });
      core.info(`Assigned release checklist: ${data.html_url}`);
      return data;
    }
    core.info(`Checklist already exists: ${existing.html_url}`);
    return existing;
  }

  const checklist = readFileSync(join(__dirname, 'checklist.md'), 'utf8');
  const body = [
    marker,
    `@${tester}, please test this Sextant build and record results below.`,
    '',
    `**Download:** [${apk.name}](${apk.browser_download_url})`,
    `**Release:** [${release.name}](${release.html_url})`,
    '',
    '> The rolling release keeps only the newest APK. If this link stops working, a newer ' +
      'build has been published and has its own checklist.',
    '',
    '## Published release details',
    '',
    release.body || 'No release notes were provided.',
    '',
    '---',
    '',
    checklist,
  ].join('\n');
  const { data: issue } = await github.rest.issues.create({
    ...repo,
    title: `Release testing: ${release.name}`,
    body,
    assignees: assignable ? [tester] : [],
  });
  if (!assignable) {
    core.warning(
      `${tester} cannot be assigned yet (pending repository invitation?). They were ` +
        `@mentioned; re-run this workflow after they accept to assign ${issue.html_url}.`,
    );
  }
  core.info(`Created release checklist: ${issue.html_url}`);
  return issue;
}

module.exports = { createReleaseTestIssue, TAG };
