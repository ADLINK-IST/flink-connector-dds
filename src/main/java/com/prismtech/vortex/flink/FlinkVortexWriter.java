package com.prismtech.vortex.flink;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.omg.dds.core.policy.QosPolicy;
import org.omg.dds.pub.DataWriter;
import org.omg.dds.pub.DataWriterQos;
import org.omg.dds.topic.Topic;
import org.omg.dds.type.TypeSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vortex.commons.serialization.QoSSerializer;
import vortex.commons.util.VConfig;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Properties;

import static java.util.Objects.requireNonNull;
import static vortex.commons.util.VConfig.DefaultEntities.*;

/**
 * Created by Vortex.
 */
public class FlinkVortexWriter<IN>
        extends RichSinkFunction<IN>
        implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(FlinkVortexWriter.class);

    private static final long serialVersionUID = 1L;


    private QosPolicy.ForDataWriter[] qos;
    private Properties writerConfig;

    private transient  Topic<IN> topic;
    private transient DataWriter<IN> dw;


    public FlinkVortexWriter(Topic<IN> topic, Properties writerConfig, QosPolicy.ForDataWriter... qos) {
        requireNonNull(topic, "Topic not set.");
        requireNonNull(writerConfig, "Writer config not set.");

        this.topic = topic;
        this.writerConfig = writerConfig;
        this.qos = qos;
    }

    // ------------ Properties ----------

    /**
     * Initializes the connection to Vortex.
     */
    @Override
    public void open(Configuration parameters) throws Exception {
        if(dw != null) {
            dw.close();
        }

        final DataWriterQos writerQos = defaultPub().getDefaultDataWriterQos().withPolicies(qos);
        dw = defaultPub().createDataWriter(topic, writerQos);

        LOG.info("Staring FlinkVortexWriter to write into topic {}", topic.getName());

    }

    /**
     * Called when new data arrives to the sink, and writes it to Vorrtex.
     *
     * @param next the incoming data
     */
    @Override
    public void invoke(IN next) throws Exception {
        dw.write(next);
    }

    @Override
    public void close() throws Exception {
        if(dw != null) {
            dw.close();
        }
    }

    private void writeObject(ObjectOutputStream o)
            throws IOException {
        o.writeObject(topic.getName());
        o.writeObject(topic.getTypeSupport().getType());
        o.writeObject(topic.getTypeSupport().getTypeName());
        o.writeObject(writerConfig);
        QoSSerializer qSerializer = new QoSSerializer(defaultPolicyFactory());
        o.writeObject(qSerializer.toSerializable(qos));
    }

    @SuppressWarnings("unchecked")
    private void readObject(ObjectInputStream o)
            throws IOException, ClassNotFoundException {
        String topipName = (String) o.readObject();
        Class<IN> topicType = (Class<IN>) o.readObject();
        String tregType = (String) o.readObject();
        writerConfig = (Properties) o.readObject();
        QoSSerializer qSerializer = new QoSSerializer(defaultPolicyFactory());
        QosPolicy[] policies = qSerializer.fromSerializable((Serializable) o.readObject());
        qos = VortexUtils.filterType(policies, QosPolicy.ForDataWriter.class);
        TypeSupport<IN> ts = TypeSupport.newTypeSupport(topicType, tregType, VConfig.ENV);
        topic = defaultDomainParticipant().createTopic(topipName, ts);
    }
}
