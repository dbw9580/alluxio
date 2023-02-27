---
layout: global
title: Project Dora
nickname: Project Dora
group: overview
priority: 7
---

* Table of Contents
{:toc}

Project Dora is the next-gen architecture for Alluxio's distributed cache, designed with scalability and
performance in mind.

## Architecture Overview

The major shift in the landscape of Alluxio's metadata and cache management in Project Dora is that there is no longer a single
master node in charge of all the file system metadata and cache information. Instead, the "workers", or simply "Dora
cache nodes," now handle both the metadata and the data of the files. Clients simply send requests to Dora cache
nodes, and each Dora cache node will serve both the metadata and the data of the requested files. Since you can have a
large number of Dora cache nodes in service, client traffic does not have to go to the single master node, but rather
is distributed among the Dora cache nodes, therefore, greatly reducing the burden of the master node.

### Load balancing between Dora nodes

Without a single master node dictating clients which workers to go to fetch the data, clients need an alternative
way to select its destination. Dora employs a simple yet effective algorithm called
[consistent hashing](https://en.wikipedia.org/wiki/Consistent_hashing) to deterministically compute a target node.
Consistent hashing ensures that given a list of all available Dora cache nodes, for a particular requested file,
any client will independently choose the same node to request the file from. If the hash algorithm used is
[uniform](https://en.wikipedia.org/wiki/Hash_function#Uniformity) over the nodes, then the requests will be uniformly
distributed among the nodes (modulo the distribution of the requests).

Consistent hashing allows the Dora cluster to scale linearly with the size of the dataset, as all the metadata and data
are partitioned onto multiple nodes, without any of them being the single point of failure.

### Fault tolerance and client-side UFS fallback

A Dora cache node can sometimes run into serious trouble and stop serving requests. To make sure the clients' requests
get served normally even if a node is faulty, there's a fallback mechanism supporting clients falling back from the
faulty node to another one, and eventually if all possible options are exhausted, falling back to the UFS.

For a given file, the target node chosen by the consistent hashing algorithm is the primary node for handling the
requests regarding this file. Consistent hashing allows a client to compute a secondary node following the
primary node's failure, and redirects the requests to it. Like the primary node, the secondary node computed
by different clients independently is exactly the same, ensuring that the fallback will happen to the same node.
This fallback process can happen a few more times (configurable by the user),
until the cost of retrying multiple nodes becomes unacceptable, when the client can fall back to the UFS directly.

### Metadata management

Dora cache nodes cache the metadata of the files they are in charge of. The metadata is fetched from the UFS directly
the first time when a file is requested by a client, and cached by the Dora node. It is then used to respond to
metadata requests afterwards.

Currently, the Dora architecture is geared towards immutable and read-only use cases only. This assumes the metadata
of the files in the UFS do not change over time, so that the cached metadata do not have to be invalidated. In the
future, we'd like to explore certain use cases where invalidation of metadata is needed but is relatively rare.

## Operational Guide 

This section covers how to set up a Dora cluster, and perform IO operations. The configurations listed below need
to be the same on all Alluxio nodes.

### 1. Enable Dora Distributed Cache

```properties
alluxio.dora.client.read.location.policy.enabled=true
```

This will enable the consistent hashing algorithm to distribute the load among Dora cache nodes.

### 2. Disable short-circuit IO and worker register lease

```properties
alluxio.user.short.circuit.enabled=false
alluxio.master.worker.register.lease.enabled=false
```

These features are not supported in Dora and needs to be disabled for Dora to work.

### 3. Enable client UFS fallback

```properties
alluxio.dora.client.ufs.root=<under_fs_uri>
```

This property specifies the UFS clients will fall back to, in the same way as the
`alluxio.master.mount.table.root.ufs` property specifies the UFS of the master root mount point.

To configure additionally UFS specific configurations, simply put them in the `alluxio-site.properties` file. Make sure
the configuration are the same across all Dora nodes.

For example, if the UFS is HDFS, and needs special configurations specified in `core-site.xml` and `hdfs-site.xml`,
specify the Alluxio property `alluxio.underfs.hdfs.configuration` directly. The documentation on
[configuring HDFS]({{ '/en/ufs/HDFS.html#specify-hdfs-configuration-location' | relativize_url }}) suggests using
the Master mount point option starting with `alluxio.master.mount.table.root.option`. This is currently not supported
by Dora nodes.

### 4. Cache storage

Configure the cache storage used by each Dora cache nodes:

```properties
alluxio.worker.block.store.type=PAGE
alluxio.worker.page.store.type=LOCAL
alluxio.worker.page.store.dirs=/mnt/ramdisk
alluxio.worker.page.store.sizes=1GB
alluxio.worker.page.store.page.size=1MB
```

The cache store used by Dora cache nodes is currently hardcoded to be the paged block store. You can refer to the
[documentation]({{ /en/core-services/Caching.html#experimental-paging-worker-storage | relativize_url }})
on how to configure the paged block store.

### 5. Tuneables

#### Dora client-side metadata cache

Set `alluxio.dora.client.metadata.cache.enabled` to `true` to enable the client-side metadata cache.
If disabled, client will always fetch metadata from Dora cache nodes.

#### High performance data transmission over Netty 

Set `alluxio.user.netty.data.transmission.enabled` to `true` to enable transmission of data between clients and
Dora cache nodes over Netty. This avoids serialization and deserialization cost of gRPC, as well as consumes less
resources on the worker side.

### Working with FUSE

Launch FUSE SDK with the same configuration (same `<ALLUXIO_HOME>/conf/`) as launching the Alluxio cluster.
Other configuration is the same as launching a standalone FUSE SDK.
```console
$ alluxio-fuse mount <under_storage_dataset> <mount_point> -o option
```
`<under_storage_dataset>` should be exactly the same as the configured `alluxio.dora.client.ufs.root`.

Optionally, you can disable default FUSE SDK local metadata cache with `-o local_metadata_cache_size=0`.


## Known limitations

1. Currently, only one UFS is supported by Dora. Nested mounts are not supported yet.
2. Currently, the Alluxio Master node still needs to be up and running. It is used for Dora worker discovery,
cluster configuration updates, as well as handling write IO operations.

