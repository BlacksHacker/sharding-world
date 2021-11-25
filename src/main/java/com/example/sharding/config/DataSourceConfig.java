package com.example.sharding.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Data;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.TableRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.strategy.StandardShardingStrategyConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * @ClassName DataSourceConfig
 * @Description
 * @Author xiaosheng1.li
 **/
@Configuration
@Data
public class DataSourceConfig {

    @Value("${datasource.account_1}")
    private HikariConfig hikariConfig_1;

    @Value("${datasource.account_2}")
    private HikariConfig hikariConfig_2;

    @Value("${datasource.account_3}")
    private HikariConfig hikariConfig_3;

    @Bean(name = "dataSource")
    public DataSource dataSource(){
        Map<String, DataSource> dataSourceMap = new HashMap(3);
        dataSourceMap.put("account_1", new HikariDataSource(hikariConfig_1));
        dataSourceMap.put("account_2", new HikariDataSource(hikariConfig_2));
        dataSourceMap.put("account_3", new HikariDataSource(hikariConfig_3));

        TableRuleConfiguration result = new TableRuleConfiguration("user", "relay.user_$->{3}");
        result.setTableShardingStrategyConfig(new StandardShardingStrategyConfiguration("", new MyTablePreciseShardingAlgorithm()));

        ShardingRuleConfiguration shardingRuleConfiguration = new ShardingRuleConfiguration();
        shardingRuleConfiguration.getTableRuleConfigs().add();
    }




}
