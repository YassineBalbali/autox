// The deployment settings that are logic rather than values. Run with `npm run test:scripts`.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (path) => readFileSync(join(repository, path), 'utf8');

describe('the Node version', () => {
  it('is the same on CI and Vercel', () => {
    const ci = read('.github/workflows/frontend.yml').match(/node-version: '([\d.]+)'/)[1];

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
