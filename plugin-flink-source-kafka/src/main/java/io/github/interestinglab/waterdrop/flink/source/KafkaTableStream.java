package io.github.interestinglab.waterdrop.flink.source;

import com.alibaba.fastjson.JSONObject;
import com.typesafe.config.waterdrop.Config;
import io.github.interestinglab.waterdrop.common.PropertiesUtil;
import io.github.interestinglab.waterdrop.common.config.CheckConfigUtil;
import io.github.interestinglab.waterdrop.common.config.TypesafeConfigUtils;
import io.github.interestinglab.waterdrop.flink.FlinkEnvironment;
import io.github.interestinglab.waterdrop.flink.stream.FlinkStreamSource;
import io.github.interestinglab.waterdrop.flink.util.SchemaUtil;
import io.github.interestinglab.waterdrop.flink.util.TableUtil;
import io.github.interestinglab.waterdrop.common.config.CheckResult;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.apache.flink.table.descriptors.*;
import org.apache.flink.types.Row;

import java.util.Properties;

/**
 * @author mr_xiong
 * @date 2019-07-03 11:46
 * @description
 */
public class KafkaTableStream implements FlinkStreamSource<Row> {

    private Config config;

    private Properties kafkaParams = new Properties();
    private String topic;
    private Object schemaInfo;
    private String rowTimeField;
    private String tableName;
    private final String consumerPrefix = "consumer.";
    private long watermark;
    private String format;

    private static final String TOPICS = "topics";
    private static final String ROWTIME_FIELD = "rowtime.field";
    private static final String WATERMARK_VAL = "watermark";
    private static final String SCHEMA = "schema";
    private static final String SOURCE_FORMAT = "source_format";
    private static final String GROUP_ID = "group.id";

    @Override
    public void setConfig(Config config) {
        this.config = config;
    }

    @Override
    public Config getConfig() {
        return config;
    }

    @Override
    public CheckResult checkConfig() {

        CheckResult result = CheckConfigUtil.check(config, TOPICS, SCHEMA, SOURCE_FORMAT, RESULT_TABLE_NAME);

        if (result.isSuccess()) {
            Config consumerConfig = TypesafeConfigUtils.extractSubConfig(config, consumerPrefix, false);
            return CheckConfigUtil.check(consumerConfig, GROUP_ID);
        }

        return result;
    }

    @Override
    public void prepare(FlinkEnvironment env) {
        topic = config.getString(TOPICS);
        PropertiesUtil.setProperties(config, kafkaParams, consumerPrefix, false);
        tableName = config.getString(RESULT_TABLE_NAME);
        if (config.hasPath(ROWTIME_FIELD)) {
            rowTimeField = config.getString(ROWTIME_FIELD);
            if (config.hasPath(WATERMARK_VAL)) {
                watermark = config.getLong(WATERMARK_VAL);
            }
        }
        String schemaContent = config.getString(SCHEMA);
        format = config.getString(SOURCE_FORMAT);
        schemaInfo = JSONObject.parse(schemaContent);
    }

    @Override
    public DataStream<Row> getData(FlinkEnvironment env) {
        StreamTableEnvironment tableEnvironment = env.getStreamTableEnvironment();
        tableEnvironment
                .connect(getKafkaConnect())
                .withFormat(setFormat())
                .withSchema(getSchema())
                .inAppendMode()
                .registerTableSource(tableName);
        Table table = tableEnvironment.scan(tableName);
        return TableUtil.tableToDataStream(tableEnvironment, table, true);
    }

    private Schema getSchema() {
        Schema schema = new Schema();
        SchemaUtil.setSchema(schema, schemaInfo, format);
        if (StringUtils.isNotBlank(rowTimeField)) {
            Rowtime rowtime = new Rowtime();
            rowtime.timestampsFromField(rowTimeField);
            rowtime.watermarksPeriodicBounded(watermark);
            schema.rowtime(rowtime);
        }
        return schema;
    }

    private Kafka getKafkaConnect() {
        Kafka kafka = new Kafka().version("universal");
        kafka.topic(topic);
        kafka.properties(kafkaParams);
        return kafka;
    }


    private FormatDescriptor setFormat() {
        try {
            return SchemaUtil.setFormat(format, config);
        } catch (Exception e) {
            e.printStackTrace();
        }
        throw new RuntimeException("format配置错误");
    }

}
