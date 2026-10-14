# Security policy

## Supported versions

Only the latest release gets fixes. Please check that a problem is still there in it before reporting.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Report it privately with GitHub's **Report a vulnerability** button, in this repository's **Security** tab: what it affects, how to reproduce it, and what it could let someone do.

Yacana is a personal and community project, so reports are answered as soon as possible, on a best-effort basis. Once a fix is released, the report is published with credit to whoever sent it, unless they prefer otherwise.

## Scope

Yacana runs on a phone, with no account or server of its own. Of interest are, for example:

- Files the app imports or downloads (sky caches, star databases, profiles, sequences, FITS) that make it write outside its folders, crash it on purpose or run code.
- Traffic on the equipment's network that makes the app do something nobody asked for.
- Anything that leaks the site's location or the user's files.

The equipment's own security, such as a Seestar's firmware, is out of scope: please report it to its maker.
