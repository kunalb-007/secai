// src/pages/DocumentDetailPage.jsx
import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
    Card, Tag, Button, Typography, Alert, Spin,
    Descriptions, Statistic, Row, Col, Empty,
} from 'antd';
import {
    ArrowLeftOutlined, CheckCircleOutlined,
    ExclamationCircleOutlined, LoadingOutlined,
    DatabaseOutlined,
} from '@ant-design/icons';

import AppLayout from '../components/AppLayout';

import { getDocument } from '../api/documents';
import { useDocumentPoller } from '../hooks/useDocumentPoller';
import dayjs from 'dayjs';

const { Title, Text, Paragraph } = Typography;

const STATUS_COLOR = {
    PENDING:    'default',
    PROCESSING: 'processing',
    READY:      'success',
    FAILED:     'error',
};

export default function DocumentDetailPage() {
    const { id }        = useParams();
    const navigate      = useNavigate();
    const [doc, setDoc] = useState(null);
    const [loading, setLoading] = useState(true);
    const [pageError, setPageError] = useState('');

    // Fetch document on mount
    useEffect(() => {
        (async () => {
            try {
                const res = await getDocument(id);
                setDoc(res.data);
            } catch {
                setPageError('Document not found or you do not have access.');
            } finally {
                setLoading(false);
            }
        })();
    }, [id]);

    // Poll if document is still being processed
    const shouldPoll = doc && (doc.status === 'PENDING' || doc.status === 'PROCESSING');

    const { status: liveStatus, chunkCount, errorMessage: liveError } =
        useDocumentPoller(shouldPoll ? id : null, (finalData) => {
            // When processing ends, update the doc state with final status
            setDoc((prev) => ({
                ...prev,
                status:       finalData.status,
                errorMessage: finalData.errorMessage,
            }));
        });

    // Use live status if polling, else use initial fetch status
    const currentStatus = (shouldPoll && liveStatus) ? liveStatus : doc?.status;

    if (loading) {
        return (
            <div style={{ textAlign: 'center', padding: 80 }}>
                <Spin size="large" />
            </div>
        );
    }

    if (pageError) {
        return (
            <div style={{ padding: 24 }}>
                <Alert type="error" message={pageError} showIcon />
                <Button
                    icon={<ArrowLeftOutlined />}
                    style={{ marginTop: 16 }}
                    onClick={() => navigate('/documents')}
                >
                    Back to Documents
                </Button>
            </div>
        );
    }

    const isReady     = currentStatus === 'READY';
    const isFailed    = currentStatus === 'FAILED';
    const isInFlight  = currentStatus === 'PENDING' || currentStatus === 'PROCESSING';

    return (
        <AppLayout>
        <div style={{ padding: 24, maxWidth: 860 }}>

            {/* Back button + heading */}
            <div style={{ marginBottom: 20 }}>
                <Button
                    type="text"
                    icon={<ArrowLeftOutlined />}
                    onClick={() => navigate('/documents')}
                    style={{ paddingLeft: 0, marginBottom: 8 }}
                >
                    All Documents
                </Button>
                <Title level={4} style={{ margin: 0 }}>{doc.filename}</Title>
            </div>

            {/* Status alert */}
            {isInFlight && (
                <Alert
                    type="info"
                    showIcon
                    icon={<LoadingOutlined />}
                    message="Processing in progress"
                    description="The AI pipeline is extracting, cleaning, chunking, and embedding your document. This page updates automatically."
                    style={{ marginBottom: 16 }}
                />
            )}

            {isReady && (
                <Alert
                    type="success"
                    showIcon
                    icon={<CheckCircleOutlined />}
                    message="Document is ready"
                    description="This document is indexed and the AI will use it to answer security questionnaires."
                    style={{ marginBottom: 16 }}
                />
            )}

            {isFailed && (
                <Alert
                    type="error"
                    showIcon
                    icon={<ExclamationCircleOutlined />}
                    message="Processing failed"
                    description={
                        <div>
                            <Paragraph style={{ marginBottom: 8 }}>
                                {doc.errorMessage || liveError || 'An unexpected error occurred.'}
                            </Paragraph>
                            <Text type="secondary">
                                <strong>What to try:</strong> If this is a PDF, convert it to TXT or DOCX and
                                re-upload. Scanned PDFs (image-only) cannot be processed automatically.
                            </Text>
                        </div>
                    }
                    style={{ marginBottom: 16 }}
                />
            )}

            {/* Metrics row */}
            {isReady && (
                <Row gutter={16} style={{ marginBottom: 24 }}>
                    <Col span={8}>
                        <Card>
                            <Statistic
                                title="Knowledge Chunks"
                                value={chunkCount ?? '—'}
                                prefix={<DatabaseOutlined />}
                                valueStyle={{ color: '#52c41a' }}
                            />
                        </Card>
                    </Col>
                    <Col span={8}>
                        <Card>
                            <Statistic
                                title="Processing Time"
                                value={
                                    doc.uploadedAt && doc.processedAt
                                        ? `${dayjs(doc.processedAt).diff(dayjs(doc.uploadedAt), 'second')}s`
                                        : '—'
                                }
                            />
                        </Card>
                    </Col>
                    <Col span={8}>
                        <Card>
                            <Statistic
                                title="Status"
                                value="Ready"
                                valueStyle={{ color: '#52c41a' }}
                                prefix={<CheckCircleOutlined />}
                            />
                        </Card>
                    </Col>
                </Row>
            )}

            {/* Document metadata */}
            <Card title="Document Info" style={{ marginBottom: 16 }}>
                <Descriptions column={2} size="small">
                    <Descriptions.Item label="Filename">{doc.filename}</Descriptions.Item>
                    <Descriptions.Item label="Status">
                        <Tag color={STATUS_COLOR[currentStatus]}>{currentStatus}</Tag>
                    </Descriptions.Item>
                    <Descriptions.Item label="Uploaded">
                        {dayjs(doc.uploadedAt).format('MMM D, YYYY HH:mm:ss')}
                    </Descriptions.Item>
                    <Descriptions.Item label="Processed">
                        {doc.processedAt
                            ? dayjs(doc.processedAt).format('MMM D, YYYY HH:mm:ss')
                            : '—'}
                    </Descriptions.Item>
                </Descriptions>
            </Card>

            {/* Actions */}
            <div style={{ display: 'flex', gap: 8 }}>
                <Button onClick={() => navigate('/documents')}>
                    Back to Documents
                </Button>
                <Button
                    type="primary"
                    onClick={() => navigate('/documents/upload')}
                >
                    Upload Another Document
                </Button>
            </div>
        </div>
        </AppLayout>
    );
}