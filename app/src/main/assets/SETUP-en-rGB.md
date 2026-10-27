# Setup help

Setting up Mail is fairly simple. You'll need to add at least one account to receive email and at least one identity if you want to send email. The quick setup will add an account and an identity in one go for most major providers.

## Requirements

An internet connection is required to set up accounts and identities.

## Quick setup

Just select the appropriate provider or *Other provider* and enter your name, email address and password and tap *Check*.

This will work for most email providers.

If the quick setup doesn't work, you'll need to set up an account and an identity manually, see below for instructions.

## Set up account - to receive email

To add an account, tap *Manual setup and more options*, tap *Accounts* and tap the 'plus' button at the bottom and select IMAP (or POP3). Select a provider from the list, enter the username, which is mostly your email address and enter your password. Tap *Check* to let Mail connect to the email server and fetch a list of system folders. After reviewing the system folder selection you can add the account by tapping *Save*.

If your provider is not in the list of providers, there are thousands of providers, select *Custom*. Enter the domain name, for example *gmail.com* and tap *Get settings*. If your provider supports [auto-discovery](https://tools.ietf.org/html/rfc6186), Mail will fill in the host name and port number, else check the setup instructions of your provider for the right IMAP host name, port number and encryption protocol (SSL/TLS or STARTTLS).

## Set up identity - to send email

Similarly, to add an identity, tap *Manual setup and more options*, tap *Identities* and tap the 'plus' button at the bottom. Enter the name you want to appear in the from address of the emails you send and select a linked account. Tap *Save* to add the identity.

If the account was configured manually, you likely need to configure the identity manually too. Enter the domain name, for example *gmail.com* and tap *Get settings*. If your provider supports [auto-discovery](https://tools.ietf.org/html/rfc6186), Mail will fill in the host name and port number, else check the setup instructions of your provider for the right SMTP host name, port number and encryption protocol (SSL/TLS or STARTTLS).

To send from an alias, add it as an identity linked to the relevant account.

## Grant permissions - to access contact information

If you want to lookup email addresses, have contact photos shown, etc, you'll need to grant permission to read contact information to Mail. Just tap *Grant* and select *Allow*.

## Setup battery optimisations - to continuously receive emails

On recent Android versions, Android will put apps to sleep when the screen is off for some time to reduce battery usage. If you want to receive new emails without delays, you should disable battery optimisations for Mail. Tap *Manage* and follow the instructions.

## Questions or problems

For account or server settings, consult your email provider's support resources. For app information, visit [anoopbhatia.in](https://anoopbhatia.in).
