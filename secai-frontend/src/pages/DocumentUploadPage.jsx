// src/pages/DocumentUploadPage.jsx
import { useState, useCallback } from 'react';
import {
    Upload, Button, Progress, Alert, Typography,
    Card, List, Tag, Steps, Spin,
} from 'antd';
import {
    InboxOutlined, FileTextOutlined, CheckCircleOutlined,
    LoadingOutlined, CloseCircleOutlined,
} from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { uploadDocument } from '../api/documents';
import { useDocumentPoller } from '../hooks/useDocumentPoller';

import AppLayout from '../components/AppLayout';

const { Dragger } = Upload;
const { Title, Text, Paragraph } = Typography;

const ALLOWED_TYPES = [
    'application/pdf',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'text/plain',
];

// Drives the Steps component during processing
const PROCESSING_STEPS = [
    'Uploading file',
    'Extracting text',
    'Cleaning & chunking',
    'Generating embeddings',
    'Indexing complete',
];

function statusToStep(status) {
    switch (status) {
        case 'PENDING':    return 1;
        case 'PROCESSING': return 2;  // we can't distinguish sub-steps from frontend
        case 'READY':      return 4;
        case 'FAILED':     return -1; // error
        default:           return 0;
    }
}

export default function DocumentUploadPage() {
    const [fileList, setFileList]             = useState([]);
    const [uploading, setUploading]           = useState(false);
    const [uploadProgress, setUploadProgress] = useState(0);
    const [uploadedDocId, setUploadedDocId]   = useState(null);   // triggers poller
    const [uploadedFilename, setUploadedFilename] = useState('');
    const [uploadError, setUploadError]       = useState('');
    const [processingDone, setProcessingDone] = useState(false);
    const navigate = useNavigate();

    // Start polling automatically once uploadedDocId is set
    const onProcessingComplete = useCallback((finalStatus) => {
        setProcessingDone(true);
    }, []);

    const {
        status:       processingStatus,
        chunkCount,
        errorMessage: processingError,
        isPolling,
    } = useDocumentPoller(uploadedDocId, onProcessingComplete);

    // ── File validation ─────────────────────────────────────────────────
    const beforeUpload = (file) => {
        const isAllowed = ALLOWED_TYPES.includes(file.type)
            || file.name.endsWith('.pdf')
            || file.name.endsWith('.docx')
            || file.name.endsWith('.txt');

        if (!isAllowed) {
            setUploadError('Only PDF, DOCX, and TXT files are allowed. DOC (old Word) is not supported.');
            return Upload.LIST_IGNORE;
        }
        if (file.size > 50 * 1024 * 1024) {
            setUploadError('File must be smaller than 50MB.');
            return Upload.LIST_IGNORE;
        }
        setFileList([file]);
        setUploadError('');
        return false; // manual upload
    };

    // ── Upload handler ──────────────────────────────────────────────────
    const handleUpload = async () => {
        if (!fileList.length) return;
        setUploading(true);
        setUploadProgress(0);
        setUploadError('');
        setUploadedDocId(null);
        setProcessingDone(false);

        try {
            const res = await uploadDocument(fileList[0], (pct) => setUploadProgress(pct));
            setUploadedDocId(res.data.documentId);
            setUploadedFilename(res.data.filename);
            setFileList([]);
        } catch (err) {
            setUploadError(err.response?.data?.error || 'Upload failed. Please try again.');
        } finally {
            setUploading(false);
        }
    };

    const handleReset = () => {
        setFileList([]);
        setUploadedDocId(null);
        setUploadedFilename('');
        setUploadError('');
        setProcessingDone(false);
        setUploadProgress(0);
    };

    // ── Render: processing in-progress ─────────────────────────────────
    if (uploadedDocId) {
        const currentStep = statusToStep(processingStatus);
        const isFailed    = processingStatus === 'FAILED';
        const isReady     = processingStatus === 'READY';

        return (
            <div style={{ maxWidth: 680, margin: '40px auto', padding: '0 24px' }}>
                <Title level={4}>Processing Document</Title>
                <Text type="secondary" style={{ display: 'block', marginBottom: 24 }}>
                    "{uploadedFilename}" was uploaded successfully. The AI pipeline is now running.
                </Text>

                {/* Pipeline steps */}
                <Card style={{ marginBottom: 24 }}>
                    <Steps
                        direction="vertical"
                        size="small"
                        current={isFailed ? undefined : currentStep}
                        status={isFailed ? 'error' : isReady ? 'finish' : 'process'}
                        items={PROCESSING_STEPS.map((label, idx) => {
                            let icon;
                            if (isFailed && idx === currentStep) {
                                icon = <CloseCircleOutlined />;
                            } else if (!isReady && idx === currentStep && !isFailed) {
                                icon = <LoadingOutlined />;
                            }
                            return { title: label, icon };
                        })}
                    />
                </Card>

                {/* READY state */}
                {isReady && (
                    <Alert
                        type="success"
                        showIcon
                        icon={<CheckCircleOutlined />}
                        message="Document indexed successfully"
                        description={
                            <span>
                <strong>{chunkCount}</strong> knowledge chunks created and ready for AI answering.
              </span>
                        }
                        action={
                            <Button
                                type="primary"
                                size="small"
                                onClick={() => navigate('/documents')}
                            >
                                View All Documents
                            </Button>
                        }
                        style={{ marginBottom: 16 }}
                    />
                )}

                {/* FAILED state */}
                {isFailed && (
                    <Alert
                        type="error"
                        showIcon
                        message="Processing failed"
                        description={processingError || 'An unexpected error occurred. Please try again.'}
                        style={{ marginBottom: 16 }}
                    />
                )}

                {/* Still processing */}
                {isPolling && !isReady && !isFailed && (
                    <div style={{ textAlign: 'center', padding: '16px 0' }}>
                        <Spin size="small" style={{ marginRight: 8 }} />
                        <Text type="secondary">This usually takes 30 seconds to 3 minutes for large PDFs…</Text>
                    </div>
                )}

                {/* Actions */}
                <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
                    <Button onClick={handleReset}>Upload Another Document</Button>
                    <Link to="/documents">
                        <Button type={isReady ? 'primary' : 'default'}>View All Documents</Button>
                    </Link>
                </div>
            </div>
        );
    }

    // ── Render: upload form ─────────────────────────────────────────────
    return (
        <AppLayout>
        <div style={{ maxWidth: 680, margin: '40px auto', padding: '0 24px' }}>
            <Title level={4}>Upload Security Document</Title>
            <Text type="secondary" style={{ display: 'block', marginBottom: 24 }}>
                Upload your security documentation. The AI will index it and use it to
                answer customer questionnaires.
            </Text>

            {uploadError && (
                <Alert
                    type="error"
                    message={uploadError}
                    style={{ marginBottom: 16 }}
                    showIcon
                    closable
                    onClose={() => setUploadError('')}
                />
            )}

            <Card>
                <Dragger
                    beforeUpload={beforeUpload}
                    fileList={fileList}
                    onRemove={() => setFileList([])}
                    accept=".pdf,.docx,.txt"
                    maxCount={1}
                    disabled={uploading}
                >
                    <p className="ant-upload-drag-icon">
                        <InboxOutlined />
                    </p>
                    <p className="ant-upload-text">Click or drag a file to upload</p>
                    <p className="ant-upload-hint">
                        PDF, DOCX, or TXT up to 50MB. One file at a time.
                    </p>
                </Dragger>

                {uploading && (
                    <Progress
                        percent={uploadProgress}
                        style={{ marginTop: 16 }}
                        status={uploadProgress < 100 ? 'active' : 'success'}
                        format={(pct) => `Uploading ${pct}%`}
                    />
                )}

                <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
                    <Button
                        type="primary"
                        onClick={handleUpload}
                        disabled={!fileList.length || uploading}
                        loading={uploading}
                        icon={<FileTextOutlined />}
                    >
                        {uploading ? 'Uploading...' : 'Upload & Index'}
                    </Button>
                    <Link to="/documents">
                        <Button disabled={uploading}>View All Documents</Button>
                    </Link>
                </div>
            </Card>

            <Card style={{ marginTop: 24 }} title="What happens after upload?">
                <Steps
                    direction="vertical"
                    size="small"
                    items={[
                        { title: 'Text extraction',    description: 'PDF via Marker AI, DOCX via Apache POI, TXT direct.' },
                        { title: 'Cleaning',           description: 'Page numbers, watermarks, and repeated headers removed.' },
                        { title: 'Chunking',           description: 'Split at section boundaries, max 300 tokens each.' },
                        { title: 'Embedding',          description: 'Each chunk converted to a 1536-dimension vector.' },
                        { title: 'Ready for answers',  description: 'AI can now search your docs to answer questionnaires.' },
                    ]}
                />
            </Card>

            <Card style={{ marginTop: 16 }} title="Supported file types">
                <List
                    size="small"
                    dataSource={[
                        { type: 'PDF',  desc: 'SOC 2 reports, pen test reports, security assessments' },
                        { type: 'DOCX', desc: 'Security policies, incident response plans, DPAs' },
                        { type: 'TXT',  desc: 'Plain text documentation' },
                    ]}
                    renderItem={(item) => (
                        <List.Item>
                            <Tag color="blue">{item.type}</Tag>
                            <Text type="secondary">{item.desc}</Text>
                        </List.Item>
                    )}
                />
            </Card>
        </div>
</AppLayout>
);
}