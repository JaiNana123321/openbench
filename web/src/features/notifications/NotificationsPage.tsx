import { Link } from 'react-router';
import type { MemberNotification } from '../../api/types';
import { ErrorPage } from '../../shell/ErrorPage';
import { useUnreadNotifications } from '../../shell/unreadNotifications';
import {
  Button,
  EmptyState,
  formatEnum,
  formatInstant,
  Loading,
  PageHeader,
  ProblemMessage,
  Table,
  type Column,
} from '../../ui';
import { useMarkRead } from './queries';

/**
 * The signed-in Member's unread notifications (SRS CI-inapp), newest first. Each links to the page
 * it concerns and can be marked read, after which it leaves the list.
 */
export function NotificationsPage() {
  const unread = useUnreadNotifications();
  const markRead = useMarkRead();

  if (unread.isError) return <ErrorPage error={unread.error} />;
  if (unread.isPending) return <Loading />;

  const { content, totalElements } = unread.data;
  const columns: Column<MemberNotification>[] = [
    { header: 'Time', cell: (notice) => formatInstant(notice.createdAt) },
    { header: 'About', cell: (notice) => formatEnum(notice.kind) },
    {
      header: 'Notice',
      cell: (notice) => (notice.link === null ? notice.text : <Link to={notice.link}>{notice.text}</Link>),
    },
    {
      header: 'Action',
      cell: (notice) => (
        <Button
          variant="secondary"
          busy={markRead.isPending && markRead.variables === notice.id}
          onClick={() => markRead.mutate(notice.id)}
          aria-label={`Mark read: ${notice.text}`}
        >
          Mark read
        </Button>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Notifications"
        subtitle={totalElements === 1 ? '1 unread notice' : `${totalElements} unread notices`}
      />
      <ProblemMessage error={markRead.error} />
      <Table
        label="Unread notifications"
        columns={columns}
        rows={content}
        rowKey={(notice) => notice.id}
        empty={<EmptyState title="No unread notifications." />}
      />
    </>
  );
}
