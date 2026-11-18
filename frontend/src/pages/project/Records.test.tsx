import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ProjectContext } from './context';
import { RecordsExplorer } from './Records';
import { jsonResponse, mockFetch } from '../../test/utils';
import type { Project } from '../../api/types';

const project: Project = { id: 'p1', name: 'Demo', slug: 'demo', description: null, createdBy: 'u', createdAt: '', updatedAt: '' };

function renderExplorer() {
  return render(
    <MemoryRouter>
      <ProjectContext.Provider value={{ project, env: 'development', setEnv: () => {}, reloadProject: () => {}, to: (p) => p }}>
        <RecordsExplorer projectId="p1" slug="todos" />
      </ProjectContext.Provider>
    </MemoryRouter>,
  );
}

const rec = (id: string, title: string) => ({
  id, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z', schemaVersion: 2, etag: `"${id}"`, data: { title },
});

describe('Records explorer', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('renders records and paginates with the cursor', async () => {
    const f = mockFetch((url) => {
      const u = new URL(url, 'http://x');
      if (!u.pathname.endsWith('/environments/development/resources/todos/records')) return undefined;
      if (u.searchParams.get('cursor') === 'next-1') {
        return jsonResponse({ success: true, data: { data: [rec('r3', 'Third')], pagination: { limit: 25, nextCursor: null } } });
      }
      return jsonResponse({ success: true, data: { data: [rec('r1', 'First'), rec('r2', 'Second')], pagination: { limit: 25, nextCursor: 'next-1' } } });
    });
    renderExplorer();
    const table = await screen.findByRole('table', { name: 'Records' });
    expect(within(table).getByText('r1')).toBeInTheDocument();
    expect(within(table).getByText('{"title":"Second"}')).toBeInTheDocument();
    expect(within(table).getAllByText('v2')).toHaveLength(2);

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('{"title":"Third"}')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    const lastUrl = new URL(f.mock.calls.at(-1)![0] as string, 'http://x');
    expect(lastUrl.searchParams.get('cursor')).toBe('next-1');
  });

  it('applies filters as JSON literals', async () => {
    const f = mockFetch(() => jsonResponse({ success: true, data: { data: [], pagination: { limit: 25, nextCursor: null } } }));
    renderExplorer();
    expect(await screen.findByText('No records')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '+ Filter' }));
    await userEvent.type(screen.getByLabelText('Filter 1 field'), 'title');
    await userEvent.type(screen.getByLabelText('Filter 1 value'), 'hello');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await screen.findByText('No records');
    const u = new URL(f.mock.calls.at(-1)![0] as string, 'http://x');
    expect(u.searchParams.get('filter[title]')).toBe('"hello"');
  });

  it('shows API errors', async () => {
    mockFetch(() => jsonResponse({ success: false, error: { code: 'NOT_FOUND', message: 'Resource not found' } }, 404));
    renderExplorer();
    expect(await screen.findByRole('alert')).toHaveTextContent('NOT_FOUND: Resource not found');
  });
});
