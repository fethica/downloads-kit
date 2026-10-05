package com.fethica.downloadskit

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

/**
 * A [DataSource.Factory] that builds the real one the first time bytes are asked for.
 *
 * A host wires its player together in `Service.onCreate` — on the main thread, and
 * inside the few seconds the platform gives a foreground service to post its
 * notification. Anything the factory opens on the disk is therefore opened in the worst
 * possible place. `createDataSource` is called from the player's loading thread, at the
 * first moment the bytes are actually wanted, which is off the main thread and is also
 * the first moment the cache is of any use.
 *
 * The delegate is a plain `by lazy`, so it is SYNCHRONIZED: two loading threads reaching
 * it at once still build one factory, and therefore one `SimpleCache` for the directory.
 * media3 throws if a second instance opens a folder that already has one.
 */
@OptIn(UnstableApi::class)
internal class LazyDataSourceFactory(
    build: () -> DataSource.Factory,
) : DataSource.Factory {

    private val delegate: DataSource.Factory by lazy(build)

    override fun createDataSource(): DataSource = delegate.createDataSource()
}
