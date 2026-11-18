import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { projects } from '../../api/endpoints';
import type { Member, MemberRole } from '../../api/types';
import { useAuth } from '../../auth';
import { Card, ConfirmDialog, Empty, ErrorBox, PageHeader, Spinner } from '../../components/ui';
import { fmtDate } from '../../components/format';
import { useAction, useAsync } from '../../hooks';
import { useProject } from './context';

const ROLES: MemberRole[] = ['OWNER', 'ADMIN', 'MEMBER'];

export function Settings() {
  const { project, reloadProject } = useProject();
  const { user } = useAuth();
  const nav = useNavigate();
  const [name, setName] = useState(project.name);
  const [slug, setSlug] = useState(project.slug);
  const [description, setDescription] = useState(project.description ?? '');
  useEffect(() => { setName(project.name); setSlug(project.slug); setDescription(project.description ?? ''); }, [project]);
  const save = useAction();
  const [saved, setSaved] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleteText, setDeleteText] = useState('');
  const del = useAction();

  const members = useAsync(() => projects.members(project.id), [project.id]);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<MemberRole>('MEMBER');
  const add = useAction();
  const memberAct = useAction();
  const [removing, setRemoving] = useState<Member | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaved(false);
    const p = await save.run(() => projects.update(project.id, { name, slug, description }));
    if (p) { setSaved(true); reloadProject(); }
  };

  return (
    <>
      <PageHeader title="Settings" />
      <Card title="Project">
        <form className="form" onSubmit={submit}>
          <div className="grid grid-2">
            <label>Name<input required maxLength={150} value={name} onChange={(e) => setName(e.target.value)} /></label>
            <label>Slug<input required maxLength={100} pattern="[a-z0-9]+(-[a-z0-9]+)*" value={slug} onChange={(e) => setSlug(e.target.value)} /></label>
          </div>
          <label>Description<textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
          <ErrorBox error={save.error} />
          {saved && <div className="alert alert-success">Saved.</div>}
          <div><button className="btn btn-primary" disabled={save.busy}>{save.busy ? 'Saving…' : 'Save changes'}</button></div>
        </form>
      </Card>

      <Card title="Members">
        <form className="row wrap" onSubmit={async (e) => {
          e.preventDefault();
          const m = await add.run(() => projects.addMember(project.id, { email, role }));
          if (m) { setEmail(''); members.reload(); }
        }}>
          <input type="email" required placeholder="teammate@example.com" aria-label="Member email" value={email} onChange={(e) => setEmail(e.target.value)} />
          <select aria-label="Role" value={role} onChange={(e) => setRole(e.target.value as MemberRole)}>{ROLES.map((r) => <option key={r}>{r}</option>)}</select>
          <button className="btn btn-primary" disabled={add.busy}>Add member</button>
        </form>
        <ErrorBox error={add.error} />
        <ErrorBox error={memberAct.error} />
        {members.loading ? <Spinner /> : members.error ? <ErrorBox error={members.error} onRetry={members.reload} /> :
          !members.data?.length ? <Empty title="No members" /> : (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Joined</th><th /></tr></thead>
                <tbody>
                  {members.data.map((m) => (
                    <tr key={m.userId}>
                      <td>{m.fullName}{m.userId === user?.id && <span className="muted small"> (you)</span>}</td>
                      <td>{m.email}</td>
                      <td>
                        <select aria-label={`Role for ${m.email}`} value={m.role} disabled={memberAct.busy}
                          onChange={async (e) => {
                            const r = e.target.value as MemberRole;
                            const ok = await memberAct.run(() => projects.updateMember(project.id, m.userId, r));
                            if (ok) members.reload();
                          }}>
                          {ROLES.map((r) => <option key={r}>{r}</option>)}
                        </select>
                      </td>
                      <td>{fmtDate(m.joinedAt)}</td>
                      <td className="right"><button className="btn btn-sm btn-danger" onClick={() => { memberAct.setError(null); setRemoving(m); }}>Remove</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </Card>

      <Card title="Danger zone">
        <p className="muted">Deleting the project removes all environments, resources, records, keys and history.</p>
        <button className="btn btn-danger" onClick={() => { del.setError(null); setDeleteText(''); setConfirmDelete(true); }}>Delete project</button>
      </Card>

      <ConfirmDialog open={!!removing} danger title="Remove member" confirmLabel="Remove"
        message={<>Remove <strong>{removing?.email}</strong> from this project?</>}
        busy={memberAct.busy} error={memberAct.error} onCancel={() => setRemoving(null)}
        onConfirm={async () => {
          if (!removing) return;
          const ok = await memberAct.run(async () => { await projects.removeMember(project.id, removing.userId); return true; });
          if (ok) { setRemoving(null); members.reload(); }
        }} />
      <ConfirmDialog open={confirmDelete} danger title="Delete project" confirmLabel="Delete project"
        message={<>
          <p>This permanently deletes <strong>{project.name}</strong>. Type the project slug <code>{project.slug}</code> to confirm.</p>
          <input aria-label="Confirm slug" value={deleteText} onChange={(e) => setDeleteText(e.target.value)} />
        </>}
        busy={del.busy} error={del.error} onCancel={() => setConfirmDelete(false)}
        onConfirm={async () => {
          if (deleteText !== project.slug) { del.setError('Slug does not match'); return; }
          const ok = await del.run(async () => { await projects.remove(project.id); return true; });
          if (ok) nav('/dashboard');
        }} />
    </>
  );
}
