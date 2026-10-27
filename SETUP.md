# Mail setup

Setting up Mail is straightforward. Add an account to receive email and an identity to send email.

## Requirements

An internet connection is required to set up accounts and identities.

## Quick setup

Choose your email provider or select *Other provider*. Enter the requested details and tap *Check*. Quick setup works with most providers. If it does not work, follow the manual setup steps below.

## Set up an account to receive email

Tap *Manual setup and more options*, open *Accounts*, tap the plus button, and choose IMAP or POP3. Select your provider, enter your username (usually your email address) and password, then tap *Check*. Mail will connect to the server and retrieve its system folders. Review the folder selection and tap *Save*.

If your provider is not listed, select *Custom*. Enter its domain name, such as *gmail.com*, then tap *Get settings*. If the provider supports [email auto-configuration](https://www.rfc-editor.org/rfc/rfc6186), Mail can fill in the server and port details. Otherwise, check your provider’s instructions for the IMAP host, port, and encryption method (SSL/TLS or STARTTLS).

## Set up an identity to send email

Tap *Manual setup and more options*, open *Identities*, and tap the plus button. Enter the name you want recipients to see and select the account to link to the identity. Tap *Save*.

If you configured the account manually, you may need to configure its sending identity manually too. Enter your provider’s domain and tap *Get settings*. If auto-configuration is unavailable, check your provider’s instructions for the SMTP host, port, and encryption method. To send from an alias, add it as another identity linked to the account.

## Grant contact permission

To look up email addresses or show contact photos, allow Mail to read your contacts. Tap *Grant*, then tap *Allow*.

## Allow background email checks

Android may put apps to sleep while the screen is off to save battery. If you want Mail to check for new email without delays, tap *Manage* and follow the instructions to adjust battery optimization.

## Questions or problems

For help, visit [anoopbhatia.in](https://anoopbhatia.in).
