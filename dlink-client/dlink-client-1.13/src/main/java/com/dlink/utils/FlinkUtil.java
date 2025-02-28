package com.dlink.utils;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.catalog.CatalogManager;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.operations.Operation;

import java.util.*;

/**
 * FlinkUtil
 *
 * @author wenmo
 * @since 2021/9/15 22:46
 */
public class FlinkUtil {

    public static List<String> getFieldNamesFromCatalogManager(CatalogManager catalogManager, String catalog, String database, String table){
        Optional<CatalogManager.TableLookupResult> tableOpt = catalogManager.getTable(
                ObjectIdentifier.of(catalog, database, table)
        );
        if (tableOpt.isPresent()) {
            return tableOpt.get().getResolvedSchema().getColumnNames();
        }else{
            return new ArrayList<String>();
        }
    }

}
