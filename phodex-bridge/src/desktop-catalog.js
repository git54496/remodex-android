// Read-only projection of the desktop's saved projects; never modify desktop state.
const fs = require('fs');
const os = require('os');
const path = require('path');

function projectThreads(threads, state) {
  if (!state || typeof state['local-projects'] !== 'object' || !state['local-projects']) {
    throw new Error('Codex desktop project catalog is unavailable; refusing to show unfiltered history.');
  }
  const projects = state['local-projects'];
  const assignments = state['thread-project-assignments'] || {};
  const projectless = new Set(state['projectless-thread-ids'] || []);
  const roots = Object.values(projects).flatMap(p => (p.rootPaths || []).map(root => ({p, root: path.resolve(root)})))
    .sort((a,b) => b.root.length - a.root.length);
  return threads.flatMap(thread => {
    const assignment = assignments[thread.id];
    let project;
    if (projectless.has(thread.id)) {
      return [{...thread, desktopGroupId: 'projectless', desktopGroupName: 'Chats', desktopProjectRoot: ''}];
    }
    if (assignment) {
      if (assignment.projectKind !== 'local' || !projects[assignment.projectId]) return [];
      project = projects[assignment.projectId];
    } else {
      const cwd = typeof thread.cwd === 'string' ? path.resolve(thread.cwd) : null;
      project = roots.find(({root}) => cwd && (cwd === root || cwd.startsWith(root + path.sep)))?.p;
    }
    if (!project) return [];
    return [{...thread, desktopGroupId: project.id, desktopGroupName: project.name,
      desktopProjectRoot: project.rootPaths?.[0] || ''}];
  });
}

function createDesktopCatalog({enabled = process.env.REMODEX_DESKTOP_CATALOG === '1', readState = () =>
  JSON.parse(fs.readFileSync(path.join(process.env.CODEX_HOME || path.join(os.homedir(), '.codex'), '.codex-global-state.json'), 'utf8'))} = {}) {
  const pending = new Map();
  return {
    request(raw) {
      if (!enabled) return raw;
      const request = JSON.parse(raw);
      if (request.method !== 'thread/list' || request.id == null) return raw;
      for (const [id, time] of pending) if (Date.now() - time > 120000) pending.delete(id);
      pending.set(String(request.id), Date.now());
      // Do not re-import deleted sessions from old rollout files.
      request.params = {...request.params, useStateDbOnly: true};
      return JSON.stringify(request);
    },
    response(raw) {
      if (!enabled) return raw;
      const response = JSON.parse(raw);
      if (response.id == null || !pending.delete(String(response.id)) || response.error) return raw;
      try {
        const result = response.result;
        const key = ['data', 'items', 'threads'].find(k => Array.isArray(result?.[k]));
        if (!key) throw new Error('Unsupported thread list response');
        result[key] = projectThreads(result[key], readState());
        // Keep pagination intact even when this page is entirely filtered out.
        return JSON.stringify(response);
      } catch (error) {
        return JSON.stringify({id: response.id, error: {code: -32000, message: error.message}});
      }
    },
  };
}
module.exports = {projectThreads, createDesktopCatalog};
