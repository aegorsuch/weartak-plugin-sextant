// Run: node --test ci/release-testing/create_release_test_issue.test.cjs
const assert = require('node:assert/strict');
const test = require('node:test');
const { createReleaseTestIssue, TAG } = require('./create_release_test_issue.cjs');

const STATE = { pipeline_id: '1791701', pipeline_iid: 108, project_id: '6910' };

function release(overrides = {}) {
  return {
    draft: false,
    tag_name: TAG,
    name: 'TAK.gov Forge CI/CD Pipeline Release (5.8.0.3-p108)',
    html_url: `https://github.com/o/r/releases/tag/${TAG}`,
    body: `- APK version: **5.8.0.3-p108**\n\n<!-- sextant-rolling-v1 ${JSON.stringify(STATE)} -->`,
    assets: [{
      name: 'Sextant-5.8.0.3-p108.apk', state: 'uploaded',
      browser_download_url: 'https://github.com/o/r/releases/download/x/Sextant-5.8.0.3-p108.apk',
    }],
    ...overrides,
  };
}

function fake({ rel = release(), issues = [], assignable = true } = {}) {
  const calls = { created: [], assigned: [] };
  const github = {
    rest: {
      repos: { getReleaseByTag: async ({ tag }) => { assert.equal(tag, TAG); return { data: rel }; } },
      issues: {
        listForRepo: 'list',
        checkUserCanBeAssigned: async () => {
          if (!assignable) throw Object.assign(new Error('not assignable'), { status: 404 });
        },
        create: async (args) => {
          calls.created.push(args);
          return { data: { ...args, html_url: 'u', assignees: args.assignees.map((login) => ({ login })) } };
        },
        addAssignees: async (args) => {
          calls.assigned.push(args);
          return { data: { html_url: 'u', assignees: [{ login: args.assignees[0] }] } };
        },
      },
    },
    paginate: {
      async *iterator(method, params) {
        assert.equal(params.state, 'all');
        yield { data: issues };
      },
    },
  };
  const logs = [];
  const core = { info: (m) => logs.push(m), warning: (m) => logs.push(`WARN ${m}`) };
  return { github, core, calls, logs };
}

const dispatch = (pipelineId = STATE.pipeline_id) => ({
  eventName: 'repository_dispatch',
  repo: { owner: 'o', repo: 'r' },
  payload: { client_payload: { pipeline_id: pipelineId } },
});

test('creates one assigned checklist with download link and release details', async () => {
  const f = fake();
  await createReleaseTestIssue({ ...f, context: dispatch(), tester: 'paul-weartak' });
  assert.equal(f.calls.created.length, 1);
  const issue = f.calls.created[0];
  assert.equal(issue.title, 'Release testing: TAK.gov Forge CI/CD Pipeline Release (5.8.0.3-p108)');
  assert.deepEqual(issue.assignees, ['paul-weartak']);
  assert.match(issue.body, /<!-- sextant-release-testing:pipeline-1791701 -->/);
  assert.match(issue.body, /\[Sextant-5\.8\.0\.3-p108\.apk\]\(https:/);
  assert.match(issue.body, /APK version: \*\*5\.8\.0\.3-p108\*\*/);
  assert.match(issue.body, /## Core acceptance checklist/);
});

test('existing checklist (even closed) is not duplicated', async () => {
  const marker = '<!-- sextant-release-testing:pipeline-1791701 -->';
  for (const state of ['open', 'closed']) {
    const f = fake({ issues: [{ state, body: marker, html_url: 'x', assignees: [{ login: 'Paul-WearTAK' }] }] });
    await createReleaseTestIssue({ ...f, context: dispatch(), tester: 'paul-weartak' });
    assert.equal(f.calls.created.length, 0);
    assert.equal(f.calls.assigned.length, 0);
  }
});

test('pending invitation: mentions without assigning, rerun assigns', async () => {
  const pending = fake({ assignable: false });
  await createReleaseTestIssue({ ...pending, context: dispatch(), tester: 'paul-weartak' });
  assert.deepEqual(pending.calls.created[0].assignees, []);
  assert.match(pending.calls.created[0].body, /^@paul-weartak/m);
  assert.ok(pending.logs.some((m) => m.startsWith('WARN')));

  const marker = '<!-- sextant-release-testing:pipeline-1791701 -->';
  const later = fake({ issues: [{ state: 'open', number: 5, body: marker, assignees: [] }] });
  await createReleaseTestIssue({ ...later, context: dispatch(), tester: 'paul-weartak' });
  assert.equal(later.calls.created.length, 0);
  assert.deepEqual(later.calls.assigned[0].assignees, ['paul-weartak']);
});

test('superseded or draft releases are skipped', async () => {
  const old = fake();
  await createReleaseTestIssue({ ...old, context: dispatch('1791600'), tester: 'paul-weartak' });
  assert.equal(old.calls.created.length, 0);
  const draft = fake({ rel: release({ draft: true }) });
  await createReleaseTestIssue({ ...draft, context: dispatch(), tester: 'paul-weartak' });
  assert.equal(draft.calls.created.length, 0);
});

test('manual run checks the current release', async () => {
  const f = fake();
  const context = { eventName: 'workflow_dispatch', repo: { owner: 'o', repo: 'r' }, payload: {} };
  await createReleaseTestIssue({ ...f, context, tester: 'paul-weartak' });
  assert.equal(f.calls.created.length, 1);
});

test('rejects bad tester, missing metadata, foreign project or missing APK', async () => {
  await assert.rejects(createReleaseTestIssue({ ...fake(), context: dispatch(), tester: '' }), /RELEASE_TESTER/);
  for (const rel of [
    release({ body: 'no marker' }),
    release({ body: `<!-- sextant-rolling-v1 ${JSON.stringify({ ...STATE, project_id: '1' })} -->` }),
  ]) {
    const f = fake({ rel });
    await assert.rejects(createReleaseTestIssue({ ...f, context: dispatch(), tester: 'paul-weartak' }), /metadata/);
  }
  const f = fake({ rel: release({ assets: [] }) });
  await assert.rejects(createReleaseTestIssue({ ...f, context: dispatch(), tester: 'paul-weartak' }), /no uploaded/);
});
