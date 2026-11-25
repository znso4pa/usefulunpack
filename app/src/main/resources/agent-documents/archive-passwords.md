# Passwords, protected headers and protected members

1. Separate failure to list from failure to extract. Some archives expose file names and protect only member content; others protect the header as well.
2. Have the user provide an archive password in the trusted local app UI or an already-authorized local workflow. Avoid reproducing it in text, command recipes or diagnostic messages.
3. Treat accepted credentials as one condition, then check output integrity and the volume set. If the app cannot distinguish a wrong password from corrupted data, report that uncertainty rather than substituting an unverified transformation.
