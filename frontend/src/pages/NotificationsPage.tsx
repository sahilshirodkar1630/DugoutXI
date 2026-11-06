import { Link } from 'react-router-dom';
import { notifications } from '../api/endpoints';
import { Card, Empty, ErrorNote, Loader, Status } from '../components/ui';
import { useAction, useLoad } from '../hooks/useLoad';
import { humanise, when } from '../lib/format';

/**
 * The inbox.
 *
 * Only notifications that have actually been sent appear here. A reminder queued for an hour before a contest sits
 * invisibly in the database until its moment arrives, so the list never shows somebody a nudge about something
 * that has not happened yet.
 */
export function NotificationsPage() {
  const inbox = useLoad(() => notifications.list(100), []);
  const { run, busy, error } = useAction();

  const markAllRead = async () => {
    const result = await run(() => notifications.markAllRead());
    if (result) inbox.reload();
  };

  const markRead = async (id: number) => {
    const result = await run(() => notifications.markRead(id));
    if (result) inbox.reload();
  };

  const unreadCount = inbox.data?.filter((item) => !item.readAt).length ?? 0;

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Alerts</h1>
          <p className="muted small">{unreadCount > 0 ? `${unreadCount} unread` : 'All caught up'}</p>
        </div>
        <button type="button" className="btn" onClick={markAllRead} disabled={busy || unreadCount === 0}>
          Mark all read
        </button>
      </div>

      <ErrorNote>{error}</ErrorNote>

      <Card>
        <Loader state={inbox} empty={<Empty>Nothing yet. Join a league and this will start filling up.</Empty>}>
          {(items) => (
            <div>
              {items.map((item) => (
                <article
                  key={item.id}
                  className={item.readAt ? 'notification' : 'notification notification-unread'}
                >
                  <span className={item.readAt ? 'dot dot-read' : 'dot'} aria-hidden="true" />
                  <div style={{ flex: 1 }}>
                    <div className="row between gap">
                      <strong>{item.title}</strong>
                      <span className="muted small">{when(item.sentAt)}</span>
                    </div>
                    {item.message && <p className="muted small" style={{ margin: '2px 0 0' }}>{item.message}</p>}
                    <div className="row gap small" style={{ marginTop: 6 }}>
                      <Status value={item.type} />
                      {item.referenceId && item.referenceType && (
                        <Link
                          to={
                            item.referenceType === 'CONTEST'
                              ? `/contests/${item.referenceId}`
                              : `/leagues/${item.referenceId}`
                          }
                        >
                          Open {humanise(item.referenceType).toLowerCase()}
                        </Link>
                      )}
                      {!item.readAt && (
                        <button
                          type="button"
                          className="btn btn-quiet btn-small"
                          onClick={() => markRead(item.id)}
                          disabled={busy}
                        >
                          Mark read
                        </button>
                      )}
                    </div>
                  </div>
                </article>
              ))}
            </div>
          )}
        </Loader>
      </Card>
    </>
  );
}
