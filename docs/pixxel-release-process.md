# Pixxel Yamcs release process

The `master` branch mirrors upstream Yamcs. Pixxel changes live on
`pixxel/master` and use versions of the form:

```text
<upstream-version>-pixxel.<release-number>
```

The POM always names the next stable release with a `-SNAPSHOT` suffix. For
example, while preparing the first Pixxel release based on Yamcs 5.13.6, the
POM contains `5.13.6-pixxel.1-SNAPSHOT`.

Every push to `pixxel/master` publishes a unique development version:

```text
5.13.6-pixxel.1.dev.<workflow-run>.<attempt>.g<commit>
```

These versions are immutable and may be pinned temporarily by integration
branches. They are never used by a released mission MCS.

To publish a stable version, tag the commit with the POM's version after
removing `-SNAPSHOT`:

```bash
git tag yamcs-5.13.6-pixxel.1
git push origin yamcs-5.13.6-pixxel.1
```

The workflow rejects a tag that does not match the next version declared by
the POM. After release, change the POM once to
`5.13.6-pixxel.2-SNAPSHOT`. When merging a new upstream version, reset the
Pixxel counter, for example to `5.13.7-pixxel.1-SNAPSHOT`.

Published package versions are never overwritten. Old development versions
may be removed later by a retention workflow, but deletion is not part of the
build or release path.

In GitHub repository settings, require the workflow check before merging into
`pixxel/master`, protect `yamcs-*` tags, and limit stable tag creation to
release maintainers. Grant mission repositories read access to the published
packages so their Actions workflows can use `GITHUB_TOKEN` instead of a PAT.
