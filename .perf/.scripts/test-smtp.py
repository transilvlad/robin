#!/usr/bin/env python3
"""
Simple SMTP test script for sending test emails.

Usage:
  python3 test-smtp.py [host] [port] [from] [to]

Examples:
  python3 test-smtp.py localhost 2525 tony@example.com pepper@example.com
  python3 test-smtp.py localhost 25 sender@test.com recipient@test.com
"""
import smtplib
import sys
from email.mime.text import MIMEText

def send_test_email(host='localhost', port=2525, mail_from='tony@example.com', mail_to='pepper@example.com'):
    """Send a simple test email via SMTP."""
    msg = MIMEText(f"Test message from {mail_from} to {mail_to}")
    msg['Subject'] = 'SMTP Test Message'
    msg['From'] = mail_from
    msg['To'] = mail_to

    try:
        with smtplib.SMTP(host, port, timeout=10) as server:
            server.sendmail(mail_from, [mail_to], msg.as_string())
        print(f"SUCCESS: Email sent from {mail_from} to {mail_to}")
        return True
    except Exception as e:
        print(f"FAILED: {e}")
        return False

if __name__ == '__main__':
    # Parse command line arguments
    host = sys.argv[1] if len(sys.argv) > 1 else 'localhost'
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 2525
    mail_from = sys.argv[3] if len(sys.argv) > 3 else 'tony@example.com'
    mail_to = sys.argv[4] if len(sys.argv) > 4 else 'pepper@example.com'

    success = send_test_email(host, port, mail_from, mail_to)
    sys.exit(0 if success else 1)
