// The deployment settings that are logic rather than values. Run with `npm run test:scripts`.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (path) => readFileSync(join(repository, path), 'utf8');

describe('the Node version', () => {
  it('is the same on CI, Netlify and Vercel', () => {
    const ci = read('.github/workflows/frontend.yml').match(/node-version: '([\d.]+)'/)[1];

    assert.equal(read('netlify.toml').match(/NODE_VERSION = "([\d.]+)"/)[1], ci);
    assert.equal(read('frontend/scripts/pinned-node.sh').match(/^VERSION=([\d.]+)$/m)[1], ci);
  });

  it('is what both Vercel commands run under', () => {
    const vercel = JSON.parse(read('frontend/vercel.json'));

    assert.match(vercel.installCommand, /^sh scripts\/pinned-node\.sh /);
    assert.match(vercel.buildCommand, /^sh scripts\/pinned-node\.sh /);
  });
});

describe('Vercel deployments', () => {
  it('are off for every branch but main', () => {
    // `**`, not `*`: in minimatch `*` stops at a slash, and that is every Dependabot branch.
    // A branch matching several rules deploys if any of them is true, so main still does.
    assert.deepEqual(JSON.parse(read('frontend/vercel.json')).git.deploymentEnabled, { '**': false, main: true });
  });
});

describe('Render deploys', () => {
  const workflow = read('.github/workflows/backend.yml');
  const job = workflow.slice(workflow.indexOf('\n  deploy:\n'));

  it('come from the backend workflow, once it has passed, for a push to main and nothing else', () => {
    assert.match(job, /^    needs: test$/m);
    assert.match(job, /^    if: github\.event_name == 'push' && github\.ref == 'refs\/heads\/main'$/m);
  });

  it('name the commit that was tested, with the hook URL read from a secret', () => {
    assert.match(job, /\$\{\{ secrets\.RENDER_DEPLOY_HOOK_URL \}\}/);
    assert.match(job, /"\$RENDER_DEPLOY_HOOK_URL&ref=\$GITHUB_SHA"/);
    assert.doesNotMatch(workflow, /api\.render\.com/);
  });

  it('are not also triggered by Render itself, which a Blueprint sync would restore', () => {
    assert.match(read('render.yaml'), /^    autoDeployTrigger: 'off'$/m);
  });
});

describe("Netlify's ignore command", () => {
  const command = read('netlify.toml').match(/^\s*ignore = '''(.+)'''\r?$/m)[1];
  let work;
  let base;
  let backendChange;
  let frontendChange;

  const git = (...args) => {
    const result = spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', ...args], {
      cwd: work,
      encoding: 'utf8',
    });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };

  const commit = (path) => {
    writeFileSync(join(work, path), path);
    git('add', '.');
    git('commit', '-m', path);
    return git('rev-parse', 'HEAD');
  };

  // As Netlify runs it: with Bash, from the base directory. 0 skips the build, 1 runs it.
  const verdict = (CONTEXT, CACHED_COMMIT_REF, COMMIT_REF) =>
    spawnSync('bash', ['-c', command], {
      cwd: join(work, 'frontend'),
      env: { ...process.env, CONTEXT, CACHED_COMMIT_REF, COMMIT_REF },
    }).status;

  before(() => {
    work = mkdtempSync(join(tmpdir(), 'autox-ignore-'));
    mkdirSync(join(work, 'frontend'));
    mkdirSync(join(work, 'backend'));
    git('init', '-q');
    base = commit('frontend/a');
    backendChange = commit('backend/b');
    frontendChange = commit('frontend/c');
  });

  after(() => rmSync(work, { recursive: true, force: true }));

  it('skips every context but production, whatever changed', () => {
    for (const context of ['deploy-preview', 'branch-deploy', 'dev']) {
      assert.equal(verdict(context, backendChange, frontendChange), 0, context);
    }
  });

  it('builds production when something under frontend/ changed', () => {
    assert.equal(verdict('production', backendChange, frontendChange), 1);
  });

  it('skips production when nothing under frontend/ changed', () => {
    assert.equal(verdict('production', base, backendChange), 0);
  });

  it('builds a manual rebuild of the same commit, the only way a build variable changes', () => {
    assert.equal(verdict('production', frontendChange, frontendChange), 1);
  });

  it('builds when the last built commit cannot be found, rather than risk skipping', () => {
    assert.equal(verdict('production', '0000000000000000000000000000000000000000', frontendChange), 1);
  });
});
