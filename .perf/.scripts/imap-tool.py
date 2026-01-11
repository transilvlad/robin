#!/usr/bin/env python3
"""
IMAP-based email delivery verification for Stalwart performance tests.

This script connects to an IMAP server and counts messages in a mailbox.
It replaces Dovecot's doveadm command for verifying test results.
"""

import argparse
import imaplib
import sys


def verify_delivery(host, port, user, password, folder='INBOX', delete_all=False):
    """Connect to IMAP server and count or delete messages."""
    try:
        # Connect to IMAP server
        if port == 993 or port == 2993:
            # Use implicit SSL/TLS for port 993/2993 (IMAPS)
            imap = imaplib.IMAP4_SSL(host, port)
        else:
            # Use plain IMAP for port 143/2143 (no TLS for testing)
            imap = imaplib.IMAP4(host, port)

        # Login
        imap.login(user, password)

        # Select folder
        status, messages = imap.select(folder)
        if status != 'OK':
            print(f"Error: Could not select folder {folder}", file=sys.stderr)
            imap.logout()
            return None

        # Get message count
        message_count = int(messages[0])

        # Delete all messages if requested
        if delete_all and message_count > 0:
            # Mark all messages for deletion
            status, data = imap.search(None, 'ALL')
            if status == 'OK':
                message_ids = data[0].split()
                for msg_id in message_ids:
                    imap.store(msg_id, '+FLAGS', '\\Deleted')
                # Expunge (permanently delete)
                imap.expunge()
                print(f"Deleted {len(message_ids)} messages from {folder}", file=sys.stderr)

        # Logout
        imap.logout()

        return message_count

    except imaplib.IMAP4.error as e:
        print(f"IMAP Error: {e}", file=sys.stderr)
        return None
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return None


def main():
    parser = argparse.ArgumentParser(
        description='Verify email delivery via IMAP',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='''
Examples:
  # Count messages in INBOX
  %(prog)s --host localhost --port 2143 --user pepper@example.com --pass potts

  # Delete all messages
  %(prog)s --host localhost --port 2143 --user pepper@example.com --pass potts --delete-all

  # Check specific folder
  %(prog)s --host localhost --port 2143 --user tony@example.com --pass stark --folder Sent
'''
    )
    parser.add_argument('--host', default='localhost', help='IMAP server host (default: localhost)')
    parser.add_argument('--port', type=int, default=993, help='IMAP server port (default: 993)')
    parser.add_argument('--user', required=True, help='Username (email address)')
    parser.add_argument('--pass', dest='password', required=True, help='Password')
    parser.add_argument('--folder', default='INBOX', help='Folder to check (default: INBOX)')
    parser.add_argument('--delete-all', action='store_true', help='Delete all messages in folder')

    args = parser.parse_args()

    count = verify_delivery(args.host, args.port, args.user, args.password, args.folder, args.delete_all)

    if count is not None:
        if not args.delete_all:
            print(f"Message count: {count}")
        sys.exit(0)
    else:
        sys.exit(1)


if __name__ == '__main__':
    main()
